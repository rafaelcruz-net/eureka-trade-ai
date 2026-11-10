package com.memetrade.privacy.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.privacy.domain.purge.PrivacyPurge;
import com.memetrade.privacy.domain.purge.PurgeHealthPolicy;
import com.memetrade.privacy.domain.purge.PurgeOutcome;
import com.memetrade.privacy.domain.purge.PurgeResult;
import com.memetrade.privacy.domain.purge.PurgeRun;
import com.memetrade.privacy.domain.purge.PurgeRunStore;
import com.memetrade.shared.health.HealthStatuses;
import com.memetrade.testsupport.InMemoryLockProvider;
import com.memetrade.testsupport.MutableClock;
import com.memetrade.testsupport.RecordingAuditPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Passo 6 of u4-data-requests (after the implementation; NFR7.1, NFR3.7): the executor of the Privacy purges without a
 * database - lock, batch loop, record of the run with its event (counts only), metrics, failures that never propagate,
 * the startup recovery and the health {@code dataSubjectRequestPurge}.
 */
@Tag("u4-data-requests")
class PrivacyJobRunnerTest {

    private static final Instant NOW = Instant.parse("2026-11-10T04:00:00Z");
    private static final PrivacyPurge PURGE = PrivacyPurge.DATA_SUBJECT_REQUEST_PURGE;

    private final MutableClock clock = new MutableClock(NOW);
    private final InMemoryLockProvider lockProvider = new InMemoryLockProvider();
    private final InMemoryRuns runs = new InMemoryRuns();
    private final RecordingAuditPublisher audit = new RecordingAuditPublisher();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final PrivacyJobMetrics metrics = new PrivacyJobMetrics(meters);

    private PrivacyJobRunner runner(AuditEventPublisher publisher, PrivacyPurgeJob job) {
        return new PrivacyJobRunner(
                new DefaultLockingTaskExecutor(lockProvider),
                List.of(job),
                runs,
                publisher,
                noOpTransactions(),
                metrics,
                PrivacyProperties.defaults(),
                clock);
    }

    @Test
    void theLoopStopsAtTheFirstShortBatchAndRecordsTheSuccessWithCountsOnly() {
        ScriptedJob job = new ScriptedJob(1000, 1000, 3);

        PurgeOutcome outcome = runner(audit, job).runLocked(PURGE).orElseThrow();

        assertThat(outcome.result()).isEqualTo(PurgeResult.SUCCEEDED);
        assertThat(outcome.deleted()).isEqualTo(2003);
        assertThat(outcome.related()).isEqualTo(2003);
        assertThat(outcome.batches()).isEqualTo(3);
        assertThat(outcome.cutoff()).isEqualTo(Instant.parse("2025-11-10T04:00:00Z"));
        AuditEventCommand event = audit.withAction("DATA_REQUESTS_PURGED").getFirst();
        assertThat(event.details())
                .containsOnlyKeys("deleted", "batches", "verificationsDeleted", "result")
                .containsEntry("deleted", "2003")
                .containsEntry("result", "SUCCEEDED");
        assertThat(event.tenantId()).isEmpty();
        assertThat(event.targetRef()).isEqualTo("purge:dataSubjectRequestPurge");
        assertThat(runs.find(PURGE).orElseThrow().lastSuccessAt()).contains(NOW);
        assertThat(meters.get("memetrade.purge.deleted")
                        .tag("purge", "dataSubjectRequestPurge")
                        .counter()
                        .count())
                .isEqualTo(2003);
    }

    @Test
    void anotherInstanceHoldingTheLockMakesTheRunReturnEmpty() {
        ScriptedJob job = new ScriptedJob(5);
        lockProvider.hold("dataSubjectRequestPurge");

        assertThat(runner(audit, job).runLocked(PURGE)).isEmpty();
        assertThat(job.calls).isZero();
    }

    @Test
    void aFailedBatchKeepsTheEarlierBatchesAndRecordsFailedWithoutPropagating() {
        ScriptedJob job = new ScriptedJob(1000);
        job.failOnCall = 2;

        PurgeOutcome outcome = runner(audit, job).runLocked(PURGE).orElseThrow();

        assertThat(outcome.result()).isEqualTo(PurgeResult.FAILED);
        assertThat(outcome.deleted()).isEqualTo(1000);
        assertThat(runs.find(PURGE).orElseThrow().lastResult()).isEqualTo(PurgeResult.FAILED);
        assertThat(meters.get("memetrade.purge.runs")
                        .tag("purge", "dataSubjectRequestPurge")
                        .tag("result", "FAILED")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void anEventThatCannotBeWrittenTurnsTheRunIntoFailedAndNothingIsRecorded() {
        AuditEventPublisher refusing = event -> {
            throw new IllegalStateException("outbox down");
        };

        PurgeOutcome outcome =
                runner(refusing, new ScriptedJob(0)).runLocked(PURGE).orElseThrow();

        assertThat(outcome.result()).isEqualTo(PurgeResult.FAILED);
        assertThat(runs.find(PURGE)).isEmpty();
    }

    @Test
    void aLockStoreFailureOnTheScheduleIsLoggedNeverPropagated() {
        PrivacyJobRunner runner = new PrivacyJobRunner(
                new DefaultLockingTaskExecutor(configuration -> {
                    throw new DataAccessResourceFailureException("lock table down");
                }),
                List.of(new ScriptedJob(0)),
                runs,
                audit,
                noOpTransactions(),
                metrics,
                PrivacyProperties.defaults(),
                clock);

        assertThatCode(() -> runner.runScheduled(PURGE)).doesNotThrowAnyException();
    }

    @Test
    void theTechnicalCauseNamesTheClassAndTheSqlStateOnly() {
        RuntimeException failure =
                new DataAccessResourceFailureException("password=secret", new SQLException("boom", "08006"));

        assertThat(PrivacyJobRunner.technicalCause(failure))
                .isEqualTo("DataAccessResourceFailureException (SQLState 08006)")
                .doesNotContain("secret");
        assertThat(PrivacyJobRunner.technicalCause(new IllegalStateException("x")))
                .isEqualTo("IllegalStateException");
    }

    @Test
    void theRecoveryRunsAPurgeWithoutASuccessAndSkipsARecentOne() {
        PrivacyJobRunner runner = runner(audit, new ScriptedJob(0, 0));
        PrivacyJobRecovery recovery =
                new PrivacyJobRecovery(runs, runner, metrics, PrivacyProperties.defaults(), clock);

        assertThat(recovery.recoverOverduePurges()).containsExactly(PURGE);
        lockProvider.release("dataSubjectRequestPurge");
        clock.advance(Duration.ofHours(23));
        assertThat(recovery.recoverOverduePurges()).isEmpty();
        clock.advance(Duration.ofHours(2));
        assertThat(recovery.recoverOverduePurges()).containsExactly(PURGE);
    }

    @Test
    void theHealthIsUpForTwoDaysThenDegradedWithoutASuccessAndReadsNoValueOfARow() {
        PrivacyPurgeHealthIndicator health = new PrivacyPurgeHealthIndicator(
                PURGE, runs, new PurgeHealthPolicy(Duration.ofHours(48), Duration.ofHours(24)), NOW, clock);

        Health fresh = health.health();
        clock.advance(Duration.ofHours(49));
        Health stale = health.health();
        runs.record(new PurgeRun(PURGE, clock.instant(), PurgeResult.SUCCEEDED, Optional.of(clock.instant()), NOW, 3));
        Health recovered = health.health();

        assertThat(fresh.getStatus()).isEqualTo(Status.UP);
        assertThat(fresh.getDetails()).containsEntry("lastResult", "NEVER_RUN");
        assertThat(stale.getStatus()).isEqualTo(HealthStatuses.DEGRADED);
        assertThat(recovered.getStatus()).isEqualTo(Status.UP);
        assertThat(recovered.getDetails()).containsKeys("purge", "lastResult", "lastRunAt", "lastSuccessAt");
    }

    @Test
    void aDatabaseFailureMakesTheHealthOnlyDegraded() {
        PurgeRunStore failing = new PurgeRunStore() {
            @Override
            public Optional<PurgeRun> find(PrivacyPurge purge) {
                throw new DataAccessResourceFailureException("down");
            }

            @Override
            public void record(PurgeRun run) {}
        };
        PrivacyPurgeHealthIndicator health = new PrivacyPurgeHealthIndicator(
                PURGE, failing, new PurgeHealthPolicy(Duration.ofHours(48), Duration.ofHours(24)), NOW, clock);

        assertThat(health.health().getStatus()).isEqualTo(HealthStatuses.DEGRADED);
        assertThat(health.health().getDetails()).containsEntry("error", "DataAccessResourceFailureException");
    }

    @Test
    void theLastSuccessGaugeIsSeededAndKeepsTheLatest() {
        metrics.lastSuccessAt(PURGE, NOW);
        metrics.lastSuccessAt(PURGE, NOW.minusSeconds(60));

        assertThat(meters.get("memetrade.purge.last.success")
                        .tag("purge", "dataSubjectRequestPurge")
                        .gauge()
                        .value())
                .isEqualTo((double) NOW.getEpochSecond());
    }

    private static PlatformTransactionManager noOpTransactions() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {}

            @Override
            public void rollback(TransactionStatus status) {}
        };
    }

    /** One batch count per call; a call past the script deletes nothing. */
    private static final class ScriptedJob implements PrivacyPurgeJob {
        private final Deque<Integer> counts = new ArrayDeque<>();
        private int calls;
        private int failOnCall;

        ScriptedJob(int... counts) {
            for (int count : counts) {
                this.counts.add(count);
            }
        }

        @Override
        public PrivacyPurge purge() {
            return PURGE;
        }

        @Override
        public Instant cutoff(Instant now) {
            return new DataSubjectRequestPurgeJob((cutoff, limit) -> null, 12).cutoff(now);
        }

        @Override
        public BatchCount deleteBatch(Instant cutoff, int limit) {
            calls++;
            if (calls == failOnCall) {
                throw new DataAccessResourceFailureException("batch failed");
            }
            int rows = counts.isEmpty() ? 0 : counts.poll();
            return new BatchCount(rows, rows);
        }

        @Override
        public Optional<String> relatedDetail() {
            return Optional.of("verificationsDeleted");
        }
    }

    private static final class InMemoryRuns implements PurgeRunStore {
        private final Map<PrivacyPurge, PurgeRun> rows = new EnumMap<>(PrivacyPurge.class);

        @Override
        public Optional<PurgeRun> find(PrivacyPurge purge) {
            return Optional.ofNullable(rows.get(purge));
        }

        @Override
        public void record(PurgeRun run) {
            PurgeRun previous = rows.get(run.purge());
            Optional<Instant> success = run.lastSuccessAt().isPresent() || previous == null
                    ? run.lastSuccessAt()
                    : previous.lastSuccessAt();
            rows.put(
                    run.purge(),
                    new PurgeRun(
                            run.purge(), run.lastRunAt(), run.lastResult(), success, run.lastCutoff(), run.deleted()));
        }
    }
}
