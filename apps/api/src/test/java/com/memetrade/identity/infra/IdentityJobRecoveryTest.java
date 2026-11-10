package com.memetrade.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.domain.purge.IdentityPurge;
import com.memetrade.identity.domain.purge.InMemoryPurgeRunStore;
import com.memetrade.identity.domain.purge.PurgeResult;
import com.memetrade.identity.domain.purge.PurgeRun;
import com.memetrade.identity.domain.purge.PurgeRunStore;
import com.memetrade.testsupport.InMemoryLockProvider;
import com.memetrade.testsupport.MutableClock;
import com.memetrade.testsupport.RecordingAuditPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Passo 4 of u2-retention (tests written after the implementation; BR2.16, NFR7.3): the startup recovery without a
 * database: which purges are overdue, the seeded gauge, and a store failure that never stops the start.
 */
@Tag("u2-retention")
@ExtendWith(OutputCaptureExtension.class)
class IdentityJobRecoveryTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    void onlyThePurgesWithoutASuccessWithin24HoursRun() {
        InMemoryPurgeRunStore runs = new InMemoryPurgeRunStore();
        Instant recent = NOW.minus(Duration.ofHours(3));
        runs.record(new PurgeRun(
                IdentityPurge.LOGIN_CHALLENGE_PURGE, recent, PurgeResult.SUCCEEDED, Optional.of(recent), recent, 4));

        List<IdentityPurge> attempted = recovery(runs).recoverOverduePurges();

        assertThat(attempted).containsExactly(IdentityPurge.SESSION_PURGE);
        assertThat(meters.get("memetrade.purge.last.success")
                        .tag("purge", "loginChallengePurge")
                        .gauge()
                        .value())
                .isEqualTo((double) recent.getEpochSecond());
        assertThat(runs.find(IdentityPurge.SESSION_PURGE).orElseThrow().lastResult())
                .isEqualTo(PurgeResult.SUCCEEDED);
    }

    @Test
    void anUnavailableStoreIsLoggedAndTheOtherPurgeIsStillTried(CapturedOutput output) {
        InMemoryPurgeRunStore runs = new InMemoryPurgeRunStore();
        runs.failWith(new DataAccessResourceFailureException("refused by db.internal:5432"));

        List<IdentityPurge> attempted = recovery(runs).recoverOverduePurges();

        assertThat(attempted).isEmpty();
        assertThat(output.getOut())
                .contains("Purge recovery could not run: purge=loginChallengePurge")
                .contains("Purge recovery could not run: purge=sessionPurge")
                .doesNotContain("db.internal");
    }

    @Test
    void aFailedLastRunWithAnOldSuccessIsRecovered() {
        InMemoryPurgeRunStore runs = new InMemoryPurgeRunStore();
        Instant old = NOW.minus(Duration.ofDays(2));
        runs.record(new PurgeRun(IdentityPurge.SESSION_PURGE, old, PurgeResult.SUCCEEDED, Optional.of(old), old, 0));
        runs.record(new PurgeRun(
                IdentityPurge.SESSION_PURGE,
                NOW.minus(Duration.ofHours(1)),
                PurgeResult.FAILED,
                Optional.empty(),
                old,
                0));
        Instant recent = NOW.minus(Duration.ofHours(1));
        runs.record(new PurgeRun(
                IdentityPurge.LOGIN_CHALLENGE_PURGE, recent, PurgeResult.SUCCEEDED, Optional.of(recent), recent, 0));

        assertThat(recovery(runs).recoverOverduePurges()).containsExactly(IdentityPurge.SESSION_PURGE);
    }

    private IdentityJobRecovery recovery(PurgeRunStore runs) {
        IdentityJobMetrics metrics = new IdentityJobMetrics(meters);
        IdentityPurgeProperties settings = IdentityPurgeProperties.defaults();
        IdentityJobRunner runner = new IdentityJobRunner(
                new DefaultLockingTaskExecutor(new InMemoryLockProvider()),
                List.of(
                        new NothingToDelete(IdentityPurge.LOGIN_CHALLENGE_PURGE),
                        new NothingToDelete(IdentityPurge.SESSION_PURGE)),
                runs,
                new RecordingAuditPublisher(),
                new PlatformTransactionManager() {
                    @Override
                    public TransactionStatus getTransaction(TransactionDefinition definition) {
                        return new SimpleTransactionStatus();
                    }

                    @Override
                    public void commit(TransactionStatus status) {
                        // In-memory collaborators only.
                    }

                    @Override
                    public void rollback(TransactionStatus status) {
                        // In-memory collaborators only.
                    }
                },
                metrics,
                settings,
                clock);
        return new IdentityJobRecovery(runs, runner, metrics, settings, clock);
    }

    /** A purge with nothing left to delete. */
    private record NothingToDelete(IdentityPurge purge) implements IdentityPurgeJob {
        @Override
        public Instant cutoff(Instant now) {
            return now.minus(Duration.ofDays(1));
        }

        @Override
        public BatchCount deleteBatch(Instant cutoff, int limit) {
            return new BatchCount(0, 0);
        }
    }
}
