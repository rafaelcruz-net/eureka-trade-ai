package com.memetrade.audit.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.memetrade.audit.domain.AuditJob;
import com.memetrade.audit.domain.AuditJobResult;
import com.memetrade.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;

/** NFR4.6: every run goes through the lock with the configured bounds and gets its own correlation id. */
@Tag("u2")
@ExtendWith(OutputCaptureExtension.class)
class AuditJobRunnerTest {

    private final MutableClock clock = new MutableClock();
    private final AuditDailyJob purge = job(AuditJob.AUDIT_RETENTION_PURGE);
    private final AuditDailyJob cleanup = job(AuditJob.AUDIT_OUTBOX_CLEANUP);

    @Test
    void aFreeLockRunsTheJobWithTheConfiguredBoundsAndANewCorrelationId() {
        AtomicReference<LockConfiguration> seen = new AtomicReference<>();
        AtomicReference<String> correlation = new AtomicReference<>();
        when(purge.run()).thenAnswer(invocation -> {
            correlation.set(MDC.get("correlationId"));
            return new AuditDailyJob.RunResult(
                    AuditJob.AUDIT_RETENTION_PURGE, AuditJobResult.COMPLETED, Instant.EPOCH, 3);
        });
        LockingTaskExecutor executing = new DefaultLockingTaskExecutor(lock -> {
            seen.set(lock);
            return Optional.of(() -> {});
        });

        assertThat(runner(executing).runLocked(AuditJob.AUDIT_RETENTION_PURGE))
                .get()
                .extracting(AuditDailyJob.RunResult::deleted)
                .isEqualTo(3L);
        assertThat(seen.get().getName()).isEqualTo("AUDIT_RETENTION_PURGE");
        assertThat(seen.get().getLockAtMostFor()).isEqualTo(Duration.ofMinutes(35));
        assertThat(seen.get().getLockAtLeastFor()).isEqualTo(Duration.ofMinutes(5));
        assertThat(correlation.get()).isNotBlank();
    }

    @Test
    void aHeldLockReturnsEmptyWithoutRunningTheJob() throws Throwable {
        LockingTaskExecutor held = new DefaultLockingTaskExecutor(lock -> Optional.empty());

        assertThat(runner(held).runLocked(AuditJob.AUDIT_OUTBOX_CLEANUP)).isEmpty();
        verify(cleanup, org.mockito.Mockito.never()).run();
    }

    @Test
    void theScheduleLogsALockStoreFailureAndDoesNotThrow(CapturedOutput output) throws Throwable {
        LockingTaskExecutor broken = mock(LockingTaskExecutor.class);
        when(broken.executeWithLock(any(LockingTaskExecutor.TaskWithResult.class), any()))
                .thenThrow(new DataAccessResourceFailureException("down"));
        AuditJobRunner runner = runner(broken);

        new AuditJobSchedule(runner).retentionPurge();
        new AuditJobSchedule(runner).outboxCleanup();

        assertThat(output.getAll()).contains("Audit job could not start: job=AUDIT_RETENTION_PURGE");
        assertThat(output.getAll()).contains("Audit job could not start: job=AUDIT_OUTBOX_CLEANUP");
    }

    @Test
    void aCheckedExceptionIsWrapped() throws Throwable {
        LockingTaskExecutor checked = mock(LockingTaskExecutor.class);
        when(checked.executeWithLock(any(LockingTaskExecutor.TaskWithResult.class), any()))
                .thenThrow(new java.io.IOException("io"));

        assertThatThrownBy(() -> runner(checked).runLocked(AuditJob.AUDIT_RETENTION_PURGE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUDIT_RETENTION_PURGE");
    }

    @Test
    void everyJobNeedsItsBean() {
        LockingTaskExecutor any = mock(LockingTaskExecutor.class);

        assertThatThrownBy(() -> new AuditJobRunner(
                        any,
                        List.of(purge),
                        AuditProperties.relayOnly(Duration.ofSeconds(1), 100, Duration.ofSeconds(60)),
                        clock))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theScheduleDelegatesToTheRunner() {
        AuditJobRunner runner = mock(AuditJobRunner.class);

        new AuditJobSchedule(runner).retentionPurge();
        new AuditJobSchedule(runner).outboxCleanup();

        verify(runner).runScheduled(AuditJob.AUDIT_RETENTION_PURGE);
        verify(runner).runScheduled(AuditJob.AUDIT_OUTBOX_CLEANUP);
    }

    private AuditJobRunner runner(LockingTaskExecutor locks) {
        return new AuditJobRunner(
                locks,
                List.of(purge, cleanup),
                AuditProperties.relayOnly(Duration.ofSeconds(1), 100, Duration.ofSeconds(60)),
                clock);
    }

    private static AuditDailyJob job(AuditJob name) {
        AuditDailyJob job = mock(AuditDailyJob.class);
        when(job.job()).thenReturn(name);
        return job;
    }
}
