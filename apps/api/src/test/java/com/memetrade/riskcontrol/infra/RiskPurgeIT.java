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
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Passo 11 of U6, WRITTEN BEFORE THE CODE (BR4.3 as amended by the NFR Design; NFR2.5, NFR10.6): the daily purge
 * deletes only approved evaluations older than 31 days (the database now()) that no rejection cites, in batches of
 * their own transactions, records every round in {@code risk_purge_run}, cleans its rows older than 90 days, runs once
 * across instances (ShedLock {@code riskcontrol-purge}) and stops starting batches after max-run.
 */
class RiskPurgeIT extends AbstractU6IT {

    /** Far above a batch of two rows on the test database, so a warm-up counted in a batch cannot pass unseen. */
    private static final Duration WARM_UP_PAUSE = Duration.ofSeconds(2);

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

    /**
     * Passo 25 (volta 2, F10, NFR2.5), WRITTEN BEFORE THE CHANGE: the round warms the path of the batch once, before
     * the first delete, and that time never enters the measured duration of a batch, batch 1 included.
     */
    @Test
    void theWarmUpRunsOncePerRoundBeforeTheFirstDeleteAndOutsideTheDurationOfEveryBatch() {
        for (int i = 0; i < 3; i++) {
            // Younger than the rows of the failure and max-run tests, so their first batches stay theirs.
            rows.evaluation(person, bot, "APPROVED", (7000 + i) + " days");
        }
        List<String> calls = new ArrayList<>();
        RiskEvaluationPurgeJob observed =
                new RiskEvaluationPurgeJob(retention, locks, metrics, properties(2, Duration.ofMinutes(30)), clock) {
                    @Override
                    protected void warmUp() {
                        calls.add("warm-up");
                        super.warmUp();
                        pause(WARM_UP_PAUSE);
                    }

                    @Override
                    protected int deleteBatch(int limit) {
                        calls.add("delete");
                        return super.deleteBatch(limit);
                    }
                };

        Round round = observed.runOnce();

        assertThat(round.result()).isEqualTo("SUCCEEDED");
        assertThat(round.batches()).isGreaterThanOrEqualTo(2);
        assertThat(calls.getFirst()).isEqualTo("warm-up");
        assertThat(calls.subList(1, calls.size())).hasSize(round.batches()).containsOnly("delete");
        assertThat(round.warmUp()).isGreaterThanOrEqualTo(WARM_UP_PAUSE);
        assertThat(round.batchDurations()).hasSize(round.batches()).allSatisfy(duration -> assertThat(duration)
                .isLessThan(WARM_UP_PAUSE));
    }

    @Test
    void aFailedWarmUpFailsTheRoundBeforeAnyDeleteAndRecordsTheCause() {
        UUID old = rows.evaluation(person, bot, "APPROVED", "7500 days");
        double failuresBefore = meters.get("riskcontrol.purge.errors").counter().count();
        List<String> deletes = new ArrayList<>();
        RiskEvaluationPurgeJob failingWarmUp =
                new RiskEvaluationPurgeJob(retention, locks, metrics, properties(2, Duration.ofMinutes(30)), clock) {
                    @Override
                    protected void warmUp() {
                        throw new IllegalStateException("injected failure of the warm-up");
                    }

                    @Override
                    protected int deleteBatch(int limit) {
                        deletes.add("delete");
                        return super.deleteBatch(limit);
                    }
                };

        Round failed = failingWarmUp.runOnce();

        assertThat(failed.ran()).isTrue();
        assertThat(failed.result()).isEqualTo("FAILED");
        assertThat(failed.deleted()).isZero();
        assertThat(failed.batches()).isZero();
        assertThat(failed.batchDurations()).isEmpty();
        assertThat(failed.failureCause()).contains("IllegalStateException");
        assertThat(deletes).isEmpty();
        assertThat(exists(old)).isTrue();
        Map<String, Object> run = run(failed);
        assertThat(run.get("result")).isEqualTo("FAILED");
        assertThat((Long) run.get("deleted_count")).isZero();
        assertThat(run.get("failure_cause")).isEqualTo("IllegalStateException");
        assertThat(meters.get("riskcontrol.purge.errors").counter().count()).isEqualTo(failuresBefore + 1);
    }

    /** The real warm-up, through the retention login: it reads the path of the batch and deletes nothing. */
    @Test
    void theWarmUpOfTheRetentionLoginDeletesNothing() {
        UUID old = rows.evaluation(person, bot, "APPROVED", "7600 days");
        long before = evaluations();

        job.warmUp();

        assertThat(exists(old)).isTrue();
        assertThat(evaluations()).isEqualTo(before);
    }

    /**
     * Passo 29 (volta 3, F10, choice B1), WRITTEN BEFORE THE CHANGE: the warm-up runs the very DELETE of batch 1 (the
     * row triggers of order_evaluation fire) in a transaction of its own and rolls it back, so every eligible row is
     * still there afterwards; only the batches delete, and the retention connection goes back to auto-commit.
     */
    @Test
    void theWarmUpRunsTheDeleteOfBatch1AndRollsItBackSoOnlyTheBatchesDelete() {
        List<UUID> old = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            old.add(rows.evaluation(person, bot, "APPROVED", (7700 + i) + " days"));
        }
        long eligibleBefore = eligible();

        try (DeleteProbe probe = DeleteProbe.counting()) {
            job.warmUp();

            assertThat(probe.deletes())
                    .as("rows the DELETE of the warm-up touched")
                    .isPositive();
            assertThat(eligible()).isEqualTo(eligibleBefore);
            assertThat(old).allMatch(this::exists);
        }

        Round round = job.runOnce();

        assertThat(round.result()).isEqualTo("SUCCEEDED");
        assertThat(old).noneMatch(this::exists);
    }

    /** Passo 29, WRITTEN BEFORE THE CHANGE: one rolled-back DELETE per round, before batch 1 and outside its time. */
    @Test
    void theRolledBackDeleteOfTheWarmUpRunsOncePerRoundBeforeBatch1() {
        for (int i = 0; i < 3; i++) {
            rows.evaluation(person, bot, "APPROVED", (7800 + i) + " days");
        }
        List<String> calls = new ArrayList<>();
        try (DeleteProbe probe = DeleteProbe.counting()) {
            RiskEvaluationPurgeJob observed =
                    new RiskEvaluationPurgeJob(
                            retention, locks, metrics, properties(2, Duration.ofMinutes(30)), clock) {
                        @Override
                        protected void warmUp() {
                            long eligibleBefore = eligible();
                            long touched = probe.deletes();
                            super.warmUp();
                            calls.add("warm-up deleted=" + (probe.deletes() > touched) + " kept="
                                    + (eligible() == eligibleBefore));
                        }

                        @Override
                        protected int deleteBatch(int limit) {
                            calls.add("delete");
                            return super.deleteBatch(limit);
                        }
                    };

            Round round = observed.runOnce();

            assertThat(round.result()).isEqualTo("SUCCEEDED");
            assertThat(calls.getFirst()).isEqualTo("warm-up deleted=true kept=true");
            assertThat(calls.subList(1, calls.size())).hasSize(round.batches()).containsOnly("delete");
        }
    }

    /**
     * Passo 29, WRITTEN BEFORE THE CHANGE: a failure inside the rolled-back DELETE of the warm-up (here a row trigger
     * that raises after the rows were deleted in the statement) fails the round before any batch: FAILED, the cause
     * is the class of the exception only, no warm-up time, nothing deleted.
     */
    @Test
    void aFailureInsideTheDeleteOfTheWarmUpFailsTheRoundWithNothingDeleted() {
        UUID old = rows.evaluation(person, bot, "APPROVED", "7900 days");
        List<String> deletes = new ArrayList<>();
        Round failed;
        try (DeleteProbe probe = DeleteProbe.failing()) {
            RiskEvaluationPurgeJob observed =
                    new RiskEvaluationPurgeJob(
                            retention, locks, metrics, properties(2, Duration.ofMinutes(30)), clock) {
                        @Override
                        protected int deleteBatch(int limit) {
                            deletes.add("delete");
                            return super.deleteBatch(limit);
                        }
                    };

            failed = observed.runOnce();
        }

        assertThat(failed.result()).isEqualTo("FAILED");
        assertThat(failed.warmUp()).isZero();
        assertThat(failed.deleted()).isZero();
        assertThat(failed.batches()).isZero();
        assertThat(deletes).isEmpty();
        assertThat(failed.failureCause())
                .hasValueSatisfying(
                        cause -> assertThat(cause).doesNotContain("riskpurgeit").doesNotContain(" "));
        assertThat(exists(old)).isTrue();
        assertThat((Long) run(failed).get("deleted_count")).isZero();
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

    private long evaluations() {
        return jdbc.sql("select count(*) from order_evaluation")
                .query(Long.class)
                .single();
    }

    /** Approved evaluations the selection of the purge can take: older than 31 days and cited by no rejection. */
    private long eligible() {
        return jdbc.sql("""
                        select count(*) from order_evaluation e
                        where e.outcome = 'APPROVED' and e.evaluated_at < now() - interval '31 days'
                          and not exists (select 1 from order_rejection r where r.evaluation_id = e.evaluation_id)
                        """).query(Long.class).single();
    }

    /**
     * TEST ONLY: an AFTER DELETE row trigger on {@code order_evaluation}, installed by the container superuser for one
     * test and dropped after it. {@link #counting()} counts the deleted rows in a sequence, which a rollback does not
     * undo, so it shows that a rolled-back DELETE ran; {@link #failing()} raises after the rows were deleted in the
     * statement. The logins of the application and of retention gain nothing (security definer, dropped at close).
     */
    private static final class DeleteProbe implements AutoCloseable {

        private static final String NAME = "riskpurgeit_delete_probe";

        static DeleteProbe counting() {
            return install("perform nextval('" + NAME + "_seq'); return null;");
        }

        static DeleteProbe failing() {
            return install("raise exception 'riskpurgeit probe: injected failure after the delete';");
        }

        private static DeleteProbe install(String body) {
            JdbcClient superuser = connect(SUPERUSER);
            drop(superuser);
            superuser.sql("create sequence " + NAME + "_seq").update();
            superuser
                    .sql("create function " + NAME + "() returns trigger language plpgsql security definer"
                            + " set search_path = public as $$ begin " + body + " end $$")
                    .update();
            superuser
                    .sql("create trigger " + NAME + " after delete on order_evaluation for each row execute function "
                            + NAME + "()")
                    .update();
            return new DeleteProbe();
        }

        long deletes() {
            return connect(SUPERUSER)
                    .sql("select coalesce(pg_sequence_last_value('" + NAME + "_seq'), 0)")
                    .query(Long.class)
                    .single();
        }

        @Override
        public void close() {
            drop(connect(SUPERUSER));
        }

        private static void drop(JdbcClient superuser) {
            superuser
                    .sql("drop trigger if exists " + NAME + " on order_evaluation")
                    .update();
            superuser.sql("drop function if exists " + NAME + "()").update();
            superuser.sql("drop sequence if exists " + NAME + "_seq").update();
        }
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during the pause of the warm-up", interrupted);
        }
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
