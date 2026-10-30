package com.memetrade.audit.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.audit.domain.AuditJob;
import com.memetrade.audit.domain.AuditJobResult;
import com.memetrade.testsupport.SteppingClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Outbox cleanup (F5, BR3.4, NFR4.3, NFR2.2, NFR7.4): rows delivered more than 7 days ago leave; rows delivered more
 * recently and PENDING rows of any age stay. Each test removes the rows it created.
 */
@Tag("u2")
class AuditOutboxCleanupIT extends AbstractAuditJobIT {

    @Autowired
    private AuditJobRunner runner;

    @Autowired
    private org.springframework.jdbc.core.simple.JdbcClient mainJdbc;

    @Autowired
    private JdbcAuditJobRunStore store;

    @Autowired
    private AuditJobMetrics metrics;

    @Test
    void aRowDeliveredEightDaysAgoLeavesAndOneDeliveredSixDaysAgoStays() {
        Instant now = clock.instant();
        UUID eightDays = insertOutbox(now.minus(Duration.ofDays(9)), now.minus(Duration.ofDays(8)));
        UUID sixDays = insertOutbox(now.minus(Duration.ofDays(7)), now.minus(Duration.ofDays(6)));
        try {
            AuditDailyJob.RunResult result =
                    runner.runLocked(AuditJob.AUDIT_OUTBOX_CLEANUP).orElseThrow();

            assertThat(result.result()).isEqualTo(AuditJobResult.COMPLETED);
            assertThat(result.cutoff()).isEqualTo(now.minus(Duration.ofDays(7)));
            assertThat(result.deleted()).isGreaterThanOrEqualTo(1);
            assertThat(outboxExists(eightDays)).isFalse();
            assertThat(outboxExists(sixDays)).isTrue();
        } finally {
            deleteOutbox(eightDays);
            deleteOutbox(sixDays);
        }
    }

    @Test
    void aPendingRowThirtyDaysOldIsNeverDeleted() {
        UUID pending = insertOutbox(clock.instant().minus(Duration.ofDays(30)), null);
        try {
            runner.runLocked(AuditJob.AUDIT_OUTBOX_CLEANUP).orElseThrow();

            assertThat(outboxExists(pending)).isTrue();
        } finally {
            deleteOutbox(pending);
        }
    }

    @Test
    void theBoundaryOfSevenDaysIsExclusive() {
        Instant cutoff = clock.instant().minus(Duration.ofDays(7));
        UUID atCutoff = insertOutbox(cutoff.minus(Duration.ofDays(1)), cutoff);
        UUID justBefore = insertOutbox(cutoff.minus(Duration.ofDays(1)), cutoff.minusNanos(1_000));
        try {
            runner.runLocked(AuditJob.AUDIT_OUTBOX_CLEANUP).orElseThrow();

            assertThat(outboxExists(atCutoff)).isTrue();
            assertThat(outboxExists(justBefore)).isFalse();
        } finally {
            deleteOutbox(atCutoff);
            deleteOutbox(justBefore);
        }
    }

    @Test
    void theCleanupRecordsItsStateAndPublishesNoAuditEvent() {
        long purgedBefore = purgedEventIds().size();

        runner.runLocked(AuditJob.AUDIT_OUTBOX_CLEANUP).orElseThrow();

        assertThat(lastResult(AuditJob.AUDIT_OUTBOX_CLEANUP)).isEqualTo("COMPLETED");
        assertThat(purgedEventIds()).hasSize((int) purgedBefore);
        assertThat(metrics.lastSuccessEpochSeconds(AuditJob.AUDIT_OUTBOX_CLEANUP))
                .isEqualTo(clock.instant().getEpochSecond());
    }

    @Test
    void theTimeLimitStopsTheCleanup() {
        Instant now = clock.instant();
        List<UUID> delivered = List.of(
                insertOutbox(now.minus(Duration.ofDays(20)), now.minus(Duration.ofDays(19))),
                insertOutbox(now.minus(Duration.ofDays(20)), now.minus(Duration.ofDays(18))),
                insertOutbox(now.minus(Duration.ofDays(20)), now.minus(Duration.ofDays(17))));
        try {
            // Reduced limit for the test only: 2 minutes, with a clock moving 1 minute on every read.
            AuditOutboxCleanup cleanup = new AuditOutboxCleanup(
                    mainJdbc,
                    store,
                    metrics,
                    reducedSettings(1, Duration.ofMinutes(2)),
                    new SteppingClock(now, Duration.ofMinutes(1)));

            AuditDailyJob.RunResult result = cleanup.run();

            assertThat(result.result()).isEqualTo(AuditJobResult.TIME_LIMIT);
            assertThat(result.deleted()).isEqualTo(1);
            assertThat(delivered.stream().filter(this::outboxExists).count()).isGreaterThanOrEqualTo(2);
            assertThat(lastResult(AuditJob.AUDIT_OUTBOX_CLEANUP)).isEqualTo("TIME_LIMIT");
        } finally {
            delivered.forEach(this::deleteOutbox);
        }
    }
}
