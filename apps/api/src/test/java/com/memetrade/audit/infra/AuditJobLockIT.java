package com.memetrade.audit.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.audit.domain.AuditJob;
import com.memetrade.audit.domain.AuditJobResult;
import com.memetrade.audit.domain.AuditJobRun;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Job state and single run (NFR4.5, NFR4.6, BR3.2): ShedLock gives one run for two simultaneous callers, the caller
 * without the lock ends without deleting and without error, and the {@code audit_job_run} row is persisted, so a new
 * store on a new connection (as after a restart) reads it back.
 */
@Tag("u2")
@ExtendWith(OutputCaptureExtension.class)
class AuditJobLockIT extends AbstractAuditJobIT {

    @Autowired
    private AuditJobRunner runner;

    @Autowired
    private JdbcAuditJobRunStore store;

    @Test
    void twoSimultaneousCallsGiveASingleRun() throws Exception {
        UUID old = insertEvent(CUTOFF.minus(Duration.ofDays(40)));
        Set<UUID> before = purgedEventIds();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Optional<AuditDailyJob.RunResult>> call = () -> {
                start.await();
                return runner.runLocked(AuditJob.AUDIT_RETENTION_PURGE);
            };
            Future<Optional<AuditDailyJob.RunResult>> first = pool.submit(call);
            Future<Optional<AuditDailyJob.RunResult>> second = pool.submit(call);
            start.countDown();
            List<Optional<AuditDailyJob.RunResult>> results =
                    List.of(first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS));

            assertThat(results.stream().filter(Optional::isPresent)).hasSize(1);
            assertThat(results.stream().filter(Optional::isEmpty)).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(purgedEventsSince(before)).hasSize(1);
        assertThat(eventExists(old)).isFalse();
    }

    @Test
    void aCallerWithoutTheLockDeletesNothingAndRaisesNoError(CapturedOutput output) {
        assertThat(runner.runLocked(AuditJob.AUDIT_RETENTION_PURGE)).isPresent();
        UUID old = insertEvent(CUTOFF.minus(Duration.ofDays(50)));
        Set<UUID> before = purgedEventIds();

        // lockAtLeastFor (5 min, database time) still holds the lock of the run that just ended.
        Optional<AuditDailyJob.RunResult> skipped = runner.runLocked(AuditJob.AUDIT_RETENTION_PURGE);

        assertThat(skipped).isEmpty();
        assertThat(eventExists(old)).isTrue();
        assertThat(purgedEventIds()).isEqualTo(before);
        assertThat(output.getAll()).doesNotContain("Audit job failed");
    }

    @Test
    void theLocksOfTheTwoJobsAreIndependent() {
        assertThat(runner.runLocked(AuditJob.AUDIT_RETENTION_PURGE)).isPresent();

        assertThat(runner.runLocked(AuditJob.AUDIT_OUTBOX_CLEANUP)).isPresent();
        // Only the audit locks: other modules (U8 kill switch reconciliation) keep their own rows in the same table.
        assertThat(jdbc.sql("select name from shedlock where name like 'AUDIT_%'")
                        .query(String.class)
                        .list())
                .containsExactlyInAnyOrder("AUDIT_RETENTION_PURGE", "AUDIT_OUTBOX_CLEANUP");
    }

    @Test
    void theJobStateIsPersistedAndReadBackByANewStoreOnANewConnection() {
        Instant start = clock.instant();
        assertThat(runner.runLocked(AuditJob.AUDIT_RETENTION_PURGE))
                .get()
                .extracting(AuditDailyJob.RunResult::result)
                .isEqualTo(AuditJobResult.COMPLETED);

        Optional<AuditJobRun> reread = new JdbcAuditJobRunStore(connect(APP)).find(AuditJob.AUDIT_RETENTION_PURGE);

        assertThat(reread).isPresent();
        assertThat(reread.get().lastResult()).isEqualTo(AuditJobResult.COMPLETED);
        assertThat(reread.get().lastRunAt()).isEqualTo(start);
        assertThat(reread.get().lastSuccessAt()).contains(start);
        assertThat(reread.get().lastCutoff()).contains(CUTOFF);
        assertThat(store.find(AuditJob.AUDIT_OUTBOX_CLEANUP)).isEmpty();
    }

    @Test
    void aFailedRunKeepsThePreviousSuccess() {
        Instant success = Instant.parse("2026-10-27T03:00:05Z");
        store.record(AuditJob.AUDIT_OUTBOX_CLEANUP, success.minusSeconds(5), AuditJobResult.COMPLETED, success, CUTOFF);

        store.record(
                AuditJob.AUDIT_OUTBOX_CLEANUP,
                clock.instant(),
                AuditJobResult.FAILED,
                clock.instant(),
                clock.instant().minus(Duration.ofDays(7)));

        AuditJobRun run = store.find(AuditJob.AUDIT_OUTBOX_CLEANUP).orElseThrow();
        assertThat(run.lastResult()).isEqualTo(AuditJobResult.FAILED);
        assertThat(run.lastSuccessAt()).contains(success);
        assertThat(run.lastRunAt()).isEqualTo(clock.instant());
    }

    @Test
    void sequentialRunsAfterTheMinimumLockTimeRunAgain() {
        List<Boolean> ran = new ArrayList<>();
        ran.add(runner.runLocked(AuditJob.AUDIT_OUTBOX_CLEANUP).isPresent());
        // Simulates the next day: the lock row has expired (as after lockAtLeastFor in database time).
        jdbc.sql("update shedlock set lock_until = now() - interval '1 second'").update();
        ran.add(runner.runLocked(AuditJob.AUDIT_OUTBOX_CLEANUP).isPresent());

        assertThat(ran).containsExactly(true, true);
    }
}
