package com.memetrade.riskcontrol.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.api.Principal;
import com.memetrade.riskcontrol.infra.RiskEvaluationPurgeJob.Round;
import com.memetrade.testsupport.AbstractU6IT;
import com.memetrade.testsupport.RiskRows;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Passo 11 of U6, WRITTEN BEFORE THE CODE (BR4.3 as amended by the NFR Design; NFR2.5, NFR10.6): the daily purge
 * deletes only approved evaluations older than 31 days (the database now()) that no rejection cites, in batches of
 * their own transactions, records every round in {@code risk_purge_run}, cleans its rows older than 90 days, runs once
 * across instances (ShedLock {@code riskcontrol-purge}) and stops starting batches after max-run.
 */
class RiskPurgeIT extends AbstractU6IT {

    @Autowired
    private RiskEvaluationPurgeJob job;

    @Autowired
    private RiskRetentionDataSource retention;

    @Autowired
    private LockingTaskExecutor locks;

    @Autowired
    private RiskControlMetrics metrics;

    @Autowired
    private MeterRegistry meters;

    private RiskRows rows;
    private Principal person;
    private UUID bot;

    @BeforeEach
    void seed() {
        rows = new RiskRows(appRole());
        person = newPerson();
        bot = UUID.randomUUID();
    }

    @Test
    void onlyTheApprovedRowOlderThan31DaysIsDeletedAndTheRoundIsRecorded() {
        UUID days29 = rows.evaluation(person, bot, "APPROVED", "29 days");
        UUID days30h12 = rows.evaluation(person, bot, "APPROVED", "30 days 12 hours");
        UUID days32 = rows.evaluation(person, bot, "APPROVED", "32 days");
        UUID rejected40 = rows.evaluation(person, bot, "REJECTED", "40 days");

        Round round = job.runOnce();

        assertThat(round.ran()).isTrue();
        assertThat(round.result()).isEqualTo("SUCCEEDED");
        assertThat(round.deleted()).isPositive();
        assertThat(exists(days32)).isFalse();
        assertThat(exists(days29)).isTrue();
        assertThat(exists(days30h12)).isTrue();
        assertThat(exists(rejected40)).isTrue();
        Map<String, Object> run = run(round);
        assertThat(run.get("result")).isEqualTo("SUCCEEDED");
        assertThat((Long) run.get("deleted_count")).isEqualTo(round.deleted());
        assertThat(run.get("failure_cause")).isNull();
        // The cut is the database now() minus 31 days, never the injectable clock of the JVM.
        Instant databaseNow =
                jdbc.sql("select now()").query(Timestamp.class).single().toInstant();
        Instant cutoff = ((Timestamp) run.get("cutoff")).toInstant();
        assertThat(Duration.between(cutoff, databaseNow.minus(Duration.ofDays(31)))
                        .abs())
                .isLessThan(Duration.ofMinutes(1));
    }

    /** The one-day margin: a row the selection leaves (30.5 days) already passes the trigger (30 days). */
    @Test
    void aDirectDeleteOfThe30AndAHalfDayRowByTheRetentionLoginPassesTheTrigger() {
        UUID days30h12 = rows.evaluation(person, bot, "APPROVED", "30 days 12 hours");

        int deleted = retentionRole()
                .sql("delete from order_evaluation where evaluation_id = :id")
                .param("id", days30h12)
                .update();

        assertThat(deleted).isEqualTo(1);
    }

    @Test
    void anApprovedEvaluationCitedByADuplicateIsNeverDeleted() {
        UUID cited = rows.evaluation(person, bot, "APPROVED", "60 days");
        rows.rejection(person, bot, cited, "DUPLICATE");

        job.runOnce();

        assertThat(exists(cited)).isTrue();
    }

    @Test
    void eachBatchIsItsOwnTransactionAFailureInTheMiddleKeepsTheDoneBatchesAndTheNextRoundContinues() {
        List<UUID> oldest = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            // The oldest rows any test writes, so the first batch of 2 is made of them.
            oldest.add(rows.evaluation(person, bot, "APPROVED", (9000 + i) + " days"));
        }
        double failuresBefore = meters.get("riskcontrol.purge.errors").counter().count();
        RiskEvaluationPurgeJob failingSecondBatch =
                new RiskEvaluationPurgeJob(retention, locks, metrics, properties(2, Duration.ofMinutes(30)), clock) {
                    private int batch;

                    @Override
                    protected int deleteBatch(int limit) {
                        if (++batch == 2) {
                            throw new IllegalStateException("injected failure of the second batch");
                        }
                        return super.deleteBatch(limit);
                    }
                };

        Round failed = failingSecondBatch.runOnce();

        assertThat(failed.result()).isEqualTo("FAILED");
        assertThat(failed.deleted()).isEqualTo(2);
        assertThat(failed.failureCause()).contains("IllegalStateException");
        assertThat(oldest.stream().filter(this::exists).count()).isEqualTo(3);
        Map<String, Object> run = run(failed);
        assertThat(run.get("result")).isEqualTo("FAILED");
        assertThat(run.get("failure_cause")).isEqualTo("IllegalStateException");
        assertThat(meters.get("riskcontrol.purge.errors").counter().count()).isEqualTo(failuresBefore + 1);

        Round next = job.runOnce();

        assertThat(next.result()).isEqualTo("SUCCEEDED");
        assertThat(oldest).noneMatch(this::exists);
    }

    @Test
    void theRoundStopsStartingBatchesAfterMaxRunAndLeavesTheRestForTheNextDay() {
        List<UUID> oldest = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            // Older than every row of the other tests but younger than those of the failure test.
            oldest.add(rows.evaluation(person, bot, "APPROVED", (8000 + i) + " days"));
        }
        RiskEvaluationPurgeJob slow =
                new RiskEvaluationPurgeJob(retention, locks, metrics, properties(2, Duration.ofMinutes(30)), clock) {
                    @Override
                    protected int deleteBatch(int limit) {
                        int deleted = super.deleteBatch(limit);
                        clock.advance(Duration.ofMinutes(31));
                        return deleted;
                    }
                };

        Round round = slow.runOnce();

        assertThat(round.result()).isEqualTo("SUCCEEDED");
        assertThat(round.batches()).isEqualTo(1);
        assertThat(oldest.stream().filter(this::exists).count()).isEqualTo(2);
    }

    @Test
    void theRunLogKeepsRowsOf89DaysAndTheRoundDeletesRowsOlderThan90Days() {
        RiskRows asRetention = new RiskRows(retentionRole());
        UUID recent = asRetention.purgeRun("89 days", "SUCCEEDED");
        UUID old = asRetention.purgeRun("91 days", "FAILED");

        job.runOnce();

        assertThat(runExists(recent)).isTrue();
        assertThat(runExists(old)).isFalse();
    }

    @Test
    void aSecondInstanceSkipsTheRoundWhileTheLockIsHeld() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService other = Executors.newSingleThreadExecutor();
        long runsBefore = runs();
        try {
            Future<?> holder = other.submit(() -> {
                try {
                    locks.executeWithLock(
                            (LockingTaskExecutor.Task) () -> {
                                held.countDown();
                                release.await(30, TimeUnit.SECONDS);
                            },
                            new LockConfiguration(
                                    Instant.now(),
                                    RiskEvaluationPurgeJob.LOCK_NAME,
                                    Duration.ofMinutes(1),
                                    Duration.ZERO));
                } catch (Throwable unexpected) {
                    throw new IllegalStateException(unexpected);
                }
                return null;
            });
            assertThat(held.await(30, TimeUnit.SECONDS)).isTrue();

            Round skipped = job.runOnce();

            assertThat(skipped.ran()).isFalse();
            assertThat(runs()).isEqualTo(runsBefore);
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            other.shutdownNow();
        }
        assertThat(job.runOnce().ran()).isTrue();
    }

    private boolean exists(UUID evaluationId) {
        return jdbc.sql("select count(*) from order_evaluation where evaluation_id = :id")
                        .param("id", evaluationId)
                        .query(Long.class)
                        .single()
                == 1;
    }

    private boolean runExists(UUID runId) {
        return jdbc.sql("select count(*) from risk_purge_run where run_id = :id")
                        .param("id", runId)
                        .query(Long.class)
                        .single()
                == 1;
    }

    private long runs() {
        return jdbc.sql("select count(*) from risk_purge_run").query(Long.class).single();
    }

    private Map<String, Object> run(Round round) {
        return jdbc.sql("select * from risk_purge_run where run_id = :id")
                .param("id", round.runId().orElseThrow())
                .query()
                .singleRow();
    }

    private static RiskControlProperties properties(int batchSize, Duration maxRun) {
        RiskControlProperties.Purge defaults = RiskControlProperties.defaults().purge();
        return new RiskControlProperties(
                RiskControlProperties.FRESHNESS_MAX_AGE,
                new RiskControlProperties.Purge(
                        defaults.cron(),
                        batchSize,
                        maxRun,
                        defaults.lockAtMostFor(),
                        defaults.selectionAge(),
                        defaults.triggerAge()));
    }
}
