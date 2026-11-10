package com.memetrade.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.identity.domain.purge.IdentityPurge;
import com.memetrade.identity.domain.purge.InMemoryPurgeRunStore;
import com.memetrade.identity.domain.purge.PurgeOutcome;
import com.memetrade.identity.domain.purge.PurgeResult;
import com.memetrade.identity.domain.purge.PurgeRun;
import com.memetrade.testsupport.InMemoryLockProvider;
import com.memetrade.testsupport.MutableClock;
import com.memetrade.testsupport.RecordingAuditPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Passo 4 of u2-retention (tests written after the implementation; NFR7.1, NFR7.4, NFR8.1): the common executor of the
 * Identity purges without a database: lock, batch loop, record of the run with its event, metrics and failures that
 * never propagate.
 */
@Tag("u2-retention")
class IdentityJobRunnerTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final InMemoryLockProvider lockProvider = new InMemoryLockProvider();
    private final InMemoryPurgeRunStore runs = new InMemoryPurgeRunStore();
    private final RecordingAuditPublisher audit = new RecordingAuditPublisher();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    void anotherInstanceHoldingTheLockMakesTheRunReturnEmptyWithoutDeleting() {
        ScriptedJob job = new ScriptedJob(IdentityPurge.LOGIN_CHALLENGE_PURGE, 5);
        lockProvider.hold("loginChallengePurge");

        assertThat(runner(audit, job).runLocked(IdentityPurge.LOGIN_CHALLENGE_PURGE))
                .isEmpty();
        assertThat(job.calls).isZero();
        assertThat(runs.find(IdentityPurge.LOGIN_CHALLENGE_PURGE)).isEmpty();
    }

    @Test
    void aPurgeWithoutItsJobIsRefused() {
        IdentityJobRunner runner = runner(audit, new ScriptedJob(IdentityPurge.LOGIN_CHALLENGE_PURGE, 0));

        assertThatThrownBy(() -> runner.runLocked(IdentityPurge.SESSION_PURGE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionPurge");
    }

    @Test
    void theLoopStopsAtTheFirstShortBatchAndRecordsTheSuccess() {
        ScriptedJob job = new ScriptedJob(IdentityPurge.SESSION_PURGE, 1000, 1000, 7);

        PurgeOutcome outcome =
                runner(audit, job).runLocked(IdentityPurge.SESSION_PURGE).orElseThrow();

        assertThat(outcome.result()).isEqualTo(PurgeResult.SUCCEEDED);
        assertThat(outcome.deleted()).isEqualTo(2007);
        assertThat(outcome.related()).isEqualTo(2007 * 2);
        assertThat(outcome.batches()).isEqualTo(3);
        PurgeRun run = runs.find(IdentityPurge.SESSION_PURGE).orElseThrow();
        assertThat(run.lastSuccessAt()).contains(NOW);
        assertThat(run.lastCutoff()).isEqualTo(NOW.minus(Duration.ofDays(30)));
        AuditEventCommand event = audit.withAction("SESSIONS_PURGED").getFirst();
        assertThat(event.details())
                .containsEntry("deleted", "2007")
                .containsEntry("digestsDeleted", "4014")
                .containsEntry("batches", "3")
                .containsEntry("result", "SUCCEEDED");
        assertThat(event.targetRef()).isEqualTo("purge:sessionPurge");
    }

    @Test
    void aFailedBatchStopsTheLoopKeepsTheCountAndRecordsFailedWithoutPropagating() {
        ScriptedJob job = new ScriptedJob(IdentityPurge.LOGIN_CHALLENGE_PURGE, 1000);
        job.failOnCall(2, new DataAccessResourceFailureException("down"));

        PurgeOutcome outcome = runner(audit, job)
                .runLocked(IdentityPurge.LOGIN_CHALLENGE_PURGE)
                .orElseThrow();

        assertThat(outcome.result()).isEqualTo(PurgeResult.FAILED);
        assertThat(outcome.deleted()).isEqualTo(1000);
        assertThat(outcome.batches()).isEqualTo(2);
        assertThat(runs.find(IdentityPurge.LOGIN_CHALLENGE_PURGE).orElseThrow().lastResult())
                .isEqualTo(PurgeResult.FAILED);
        assertThat(audit.withAction("LOGIN_CHALLENGES_PURGED").getFirst().details())
                .containsEntry("result", "FAILED");
        assertThat(meters.get("memetrade.purge.runs")
                        .tag("purge", "loginChallengePurge")
                        .tag("result", "FAILED")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void anEventThatCannotBeWrittenTurnsTheRunIntoFailedAndNothingIsRecorded() {
        AuditEventPublisher refusing = event -> {
            throw new IllegalStateException("outbox unavailable");
        };
        ScriptedJob job = new ScriptedJob(IdentityPurge.LOGIN_CHALLENGE_PURGE, 3);

        PurgeOutcome outcome = runner(refusing, job)
                .runLocked(IdentityPurge.LOGIN_CHALLENGE_PURGE)
                .orElseThrow();

        assertThat(outcome.result()).isEqualTo(PurgeResult.FAILED);
        assertThat(outcome.deleted()).isEqualTo(3);
        assertThat(runs.find(IdentityPurge.LOGIN_CHALLENGE_PURGE)).isEmpty();
    }

    @Test
    void theScheduleSwallowsALockStoreFailure() {
        IdentityJobRunner runner = new IdentityJobRunner(
                new DefaultLockingTaskExecutor(configuration -> {
                    throw new DataAccessResourceFailureException("shedlock unreachable");
                }),
                List.of(new ScriptedJob(IdentityPurge.SESSION_PURGE, 0)),
                runs,
                audit,
                noOpTransactions(),
                new IdentityJobMetrics(meters),
                IdentityPurgeProperties.defaults(),
                clock);

        assertThatCode(() -> runner.runScheduled(IdentityPurge.SESSION_PURGE)).doesNotThrowAnyException();
        assertThatThrownBy(() -> runner.runLocked(IdentityPurge.SESSION_PURGE))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void theLastSuccessGaugeIsNaNBeforeTheFirstSuccessAndKeepsTheLatestSeed() {
        IdentityJobMetrics metrics = new IdentityJobMetrics(meters);
        double before = meters.get("memetrade.purge.last.success")
                .tag("purge", "sessionPurge")
                .gauge()
                .value();
        metrics.lastSuccessAt(IdentityPurge.SESSION_PURGE, NOW);
        metrics.lastSuccessAt(IdentityPurge.SESSION_PURGE, NOW.minus(Duration.ofDays(1)));

        assertThat(before).isNaN();
        assertThat(meters.get("memetrade.purge.last.success")
                        .tag("purge", "sessionPurge")
                        .gauge()
                        .value())
                .isEqualTo((double) NOW.getEpochSecond());
    }

    @Test
    void theTechnicalCauseNamesTheClassAndTheSqlStateNeverTheMessage() {
        RuntimeException wrapped =
                new DataAccessResourceFailureException("secret detail", new SQLException("password=hunter2", "08001"));

        assertThat(IdentityJobRunner.technicalCause(wrapped))
                .isEqualTo("DataAccessResourceFailureException (SQLState 08001)");
        assertThat(IdentityJobRunner.technicalCause(new IllegalStateException("value")))
                .isEqualTo("IllegalStateException");
    }

    @Test
    void thePropertiesRefuseAValueOutsideTheDesignNamingTheProperty() {
        IdentityPurgeProperties defaults = IdentityPurgeProperties.defaults();

        assertThat(defaults.challengeRetention()).isEqualTo(Duration.ofHours(24));
        assertThat(defaults.sessionRetention()).isEqualTo(Duration.ofDays(30));
        assertThat(defaults.degradedAfter()).isEqualTo(Duration.ofHours(48));
        assertThatThrownBy(() -> PurgeFixtures.withBatchSize(1001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memetrade.identity.purge.batch-size");
        assertThatThrownBy(
                        () -> new IdentityPurgeProperties(null, null, Duration.ZERO, null, 0, null, null, null, null))
                .hasMessageContaining("challenge-retention");
        assertThatThrownBy(() -> new IdentityPurgeProperties(
                        null, null, null, null, 0, Duration.ofMinutes(1), Duration.ofMinutes(5), null, null))
                .hasMessageContaining("lock-at-least-for");
    }

    private IdentityJobRunner runner(AuditEventPublisher publisher, IdentityPurgeJob job) {
        return new IdentityJobRunner(
                new DefaultLockingTaskExecutor(lockProvider),
                List.of(job),
                runs,
                publisher,
                noOpTransactions(),
                new IdentityJobMetrics(meters),
                IdentityPurgeProperties.defaults(),
                clock);
    }

    /** A transaction manager that only runs the callback (the store and the publisher are in memory). */
    private static PlatformTransactionManager noOpTransactions() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                // Nothing to commit: every collaborator of these tests is in memory.
            }

            @Override
            public void rollback(TransactionStatus status) {
                // Nothing to roll back: every collaborator of these tests is in memory.
            }
        };
    }

    /** A purge whose batches return the scripted counts (then 0), with two related rows per row. */
    private static final class ScriptedJob implements IdentityPurgeJob {
        private final IdentityPurge purge;
        private final Deque<Integer> counts = new ArrayDeque<>();
        private int calls;
        private int failingCall;
        private RuntimeException failure;

        ScriptedJob(IdentityPurge purge, int... counts) {
            this.purge = purge;
            for (int count : counts) {
                this.counts.add(count);
            }
        }

        void failOnCall(int call, RuntimeException failure) {
            this.failingCall = call;
            this.failure = failure;
        }

        @Override
        public IdentityPurge purge() {
            return purge;
        }

        @Override
        public Instant cutoff(Instant now) {
            return purge == IdentityPurge.SESSION_PURGE
                    ? now.minus(Duration.ofDays(30))
                    : now.minus(Duration.ofHours(24));
        }

        @Override
        public BatchCount deleteBatch(Instant cutoff, int limit) {
            calls++;
            if (calls == failingCall) {
                throw failure;
            }
            int rows = Optional.ofNullable(counts.poll()).orElse(0);
            return new BatchCount(rows, rows * 2);
        }

        @Override
        public Optional<String> relatedDetail() {
            return purge == IdentityPurge.SESSION_PURGE ? Optional.of("digestsDeleted") : Optional.empty();
        }
    }
}
