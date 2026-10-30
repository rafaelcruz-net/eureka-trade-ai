package com.memetrade.audit.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.audit.domain.AuditJob;
import com.memetrade.audit.domain.AuditJobResult;
import com.memetrade.testsupport.SteppingClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Retention purge (F4, BR3.1 to BR3.3, NFR3.3, NFR4.4, NFR7.4, NFR13.1) on the real database with the RETENTION login.
 * The database trigger uses the database clock; events are placed well inside or outside the window, and the
 * cutoff boundary is checked against the injected clock (2026-10-28T12:00Z, cutoff 2025-10-28T00:00Z).
 */
@Tag("u2")
class AuditRetentionPurgeIT extends AbstractAuditJobIT {

    @Autowired
    private AuditJobRunner runner;

    @Autowired
    private AuditRetentionDataSource retention;

    @Autowired
    private AuditEventPublisher publisher;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private JdbcAuditJobRunStore store;

    @Autowired
    private AuditJobMetrics metrics;

    @Test
    void anEventAtTheCutoffStaysAndOneAnInstantBeforeIsDeleted() {
        UUID atCutoff = insertEvent(CUTOFF);
        UUID justBefore = insertEvent(CUTOFF.minusNanos(1_000));

        AuditDailyJob.RunResult result = runPurge();

        assertThat(result.result()).isEqualTo(AuditJobResult.COMPLETED);
        assertThat(result.cutoff()).isEqualTo(CUTOFF);
        assertThat(eventExists(atCutoff)).isTrue();
        assertThat(eventExists(justBefore)).isFalse();
    }

    @Test
    void nothingInsideTheTwelveMonthsIsDeleted() {
        List<UUID> recent = List.of(
                insertEvent(CUTOFF.plus(Duration.ofDays(1))),
                insertEvent(clock.instant().minus(Duration.ofDays(330))),
                insertEvent(clock.instant().minus(Duration.ofDays(1))));
        UUID thirteenMonths = insertEvent(clock.instant().minus(Duration.ofDays(395)));

        runPurge();

        assertThat(recent).allMatch(this::eventExists);
        assertThat(eventExists(thirteenMonths)).isFalse();
        assertThat(eventsBefore(CUTOFF)).isZero();
    }

    @Test
    void theRunPublishesAuditRetentionPurgedWithCutoffCountAndResult() throws Exception {
        insertEvent(CUTOFF.minus(Duration.ofDays(3)));
        insertEvent(CUTOFF.minus(Duration.ofDays(2)));
        Set<UUID> before = purgedEventIds();

        AuditDailyJob.RunResult result = runPurge();

        List<JsonNode> events = purgedEventsSince(before);
        assertThat(events).hasSize(1);
        JsonNode event = events.getFirst();
        assertThat(event.get("actor").asText()).isEqualTo("system");
        assertThat(event.get("actorType").asText()).isEqualTo("SYSTEM");
        assertThat(event.get("tenantId").isNull()).isTrue();
        assertThat(event.get("correlationId").asText()).isNotBlank();
        assertThat(event.get("details").get("cutoff").asText()).isEqualTo(CUTOFF.toString());
        assertThat(event.get("details").get("result").asText()).isEqualTo("COMPLETED");
        assertThat(event.get("details").get("deletedCount").asLong())
                .isEqualTo(result.deleted())
                .isGreaterThanOrEqualTo(2);
        assertThat(lastResult(AuditJob.AUDIT_RETENTION_PURGE)).isEqualTo("COMPLETED");
        assertThat(metrics.lastSuccessEpochSeconds(AuditJob.AUDIT_RETENTION_PURGE))
                .isEqualTo(clock.instant().getEpochSecond());
    }

    @Test
    void aFailedBatchStopsTheRunKeepsWhatWasDeletedAndStillPublishesTheEvent() throws Exception {
        insertEvent(CUTOFF.minus(Duration.ofDays(9)));
        insertEvent(CUTOFF.minus(Duration.ofDays(8)));
        insertEvent(CUTOFF.minus(Duration.ofDays(7)));
        long oldBefore = eventsBefore(CUTOFF);
        Set<UUID> before = purgedEventIds();
        AuditRetentionPurge failingSecondBatch =
                new AuditRetentionPurge(
                        retention,
                        publisher,
                        transactions,
                        store,
                        metrics,
                        reducedSettings(2, Duration.ofMinutes(30)),
                        clock) {
                    private int batches;

                    @Override
                    protected int deleteBatch(Instant cutoff, int limit) {
                        if (++batches == 2) {
                            throw new DataAccessResourceFailureException("simulated loss of the retention connection");
                        }
                        return super.deleteBatch(cutoff, limit);
                    }
                };

        AuditDailyJob.RunResult result = failingSecondBatch.run();

        assertThat(result.result()).isEqualTo(AuditJobResult.FAILED);
        assertThat(result.deleted()).isEqualTo(2);
        assertThat(eventsBefore(CUTOFF)).isEqualTo(oldBefore - 2);
        JsonNode event = purgedEventsSince(before).getFirst();
        assertThat(event.get("details").get("result").asText()).isEqualTo("FAILED");
        assertThat(event.get("details").get("deletedCount").asText()).isEqualTo("2");
        assertThat(lastResult(AuditJob.AUDIT_RETENTION_PURGE)).isEqualTo("FAILED");
    }

    @Test
    void withoutTheDeletePrivilegeTheRunFailsAndTheEventIsStillRecorded() throws Exception {
        UUID old = insertEvent(CUTOFF.minus(Duration.ofDays(20)));
        Set<UUID> before = purgedEventIds();
        connect(OWNER).sql("revoke delete on audit_event from audit_retention").update();
        AuditDailyJob.RunResult result;
        try {
            result = runPurge();
        } finally {
            connect(OWNER).sql("grant delete on audit_event to audit_retention").update();
        }

        assertThat(result.result()).isEqualTo(AuditJobResult.FAILED);
        assertThat(result.deleted()).isZero();
        assertThat(eventExists(old)).isTrue();
        JsonNode event = purgedEventsSince(before).getFirst();
        assertThat(event.get("details").get("result").asText()).isEqualTo("FAILED");
        assertThat(event.get("details").get("deletedCount").asText()).isEqualTo("0");
    }

    @Test
    void theTimeLimitStopsTheRunBeforeANewBatch() throws Exception {
        List<UUID> old = List.of(
                insertEvent(CUTOFF.minus(Duration.ofDays(30))),
                insertEvent(CUTOFF.minus(Duration.ofDays(29))),
                insertEvent(CUTOFF.minus(Duration.ofDays(28))),
                insertEvent(CUTOFF.minus(Duration.ofDays(27))),
                insertEvent(CUTOFF.minus(Duration.ofDays(26))));
        Set<UUID> before = purgedEventIds();
        // Reduced limit for the test only: 2 minutes, with a clock moving 1 minute on every read.
        SteppingClock stepping = new SteppingClock(clock.instant(), Duration.ofMinutes(1));
        AuditRetentionPurge purge = new AuditRetentionPurge(
                retention,
                publisher,
                transactions,
                store,
                metrics,
                reducedSettings(1, Duration.ofMinutes(2)),
                stepping);

        AuditDailyJob.RunResult result = purge.run();

        assertThat(result.result()).isEqualTo(AuditJobResult.TIME_LIMIT);
        assertThat(result.deleted()).isEqualTo(1);
        assertThat(old.stream().filter(this::eventExists).count()).isGreaterThanOrEqualTo(4);
        assertThat(purgedEventsSince(before)
                        .getFirst()
                        .get("details")
                        .get("result")
                        .asText())
                .isEqualTo("TIME_LIMIT");
        assertThat(lastResult(AuditJob.AUDIT_RETENTION_PURGE)).isEqualTo("TIME_LIMIT");
    }

    @Test
    void theCutoffIsTheSameWithTheClockInTheBrasiliaZone() {
        // 22:30 on the 28th in Brasília is 01:30 on the 29th in UTC: the cutoff is 2025-10-29T00:00Z, not 03:00Z.
        Clock brasilia = Clock.fixed(Instant.parse("2026-10-29T01:30:00Z"), ZoneId.of("America/Sao_Paulo"));
        UUID beforeUtcCutoff = insertEvent(Instant.parse("2025-10-28T23:00:00Z"));
        UUID atUtcCutoff = insertEvent(Instant.parse("2025-10-29T00:00:00Z"));
        AuditRetentionPurge purge = new AuditRetentionPurge(
                retention,
                publisher,
                transactions,
                store,
                metrics,
                reducedSettings(1000, Duration.ofMinutes(30)),
                brasilia);

        AuditDailyJob.RunResult result = purge.run();

        assertThat(result.cutoff()).isEqualTo(Instant.parse("2025-10-29T00:00:00Z"));
        assertThat(eventExists(beforeUtcCutoff)).isFalse();
        assertThat(eventExists(atUtcCutoff)).isTrue();
    }

    @Test
    void theRetentionLoginIsTheOneThatDeletes() {
        UUID old = insertEvent(CUTOFF.minus(Duration.ofDays(60)));

        assertThat(retention
                        .jdbc()
                        .sql("select current_user")
                        .query(String.class)
                        .single())
                .isEqualTo(RETENTION.user());
        runPurge();

        assertThat(eventExists(old)).isFalse();
    }

    /**
     * Regression of the review finding "purge cutoff clock": the purge took its cutoff from the JVM clock while the
     * trigger uses the database now(); with a JVM clock ahead of the database (near UTC midnight, or skewed), the batch
     * asked for events the trigger still protects and the run ended FAILED. Now the earlier of the two cutoffs is
     * used and recorded, the run completes and nothing inside the database window is touched.
     */
    @Test
    void aJvmClockAheadOfTheDatabaseStillCompletesWithTheDatabaseCutoff() throws Exception {
        java.time.Instant databaseCutoff = jdbc.sql("select " + AuditRetentionPurge.DATABASE_CUTOFF)
                .query(java.sql.Timestamp.class)
                .single()
                .toInstant();
        java.time.Instant databaseNow = jdbc.sql("select now()")
                .query(java.sql.Timestamp.class)
                .single()
                .toInstant();
        clock.set(databaseNow.plus(Duration.ofDays(20)));
        UUID protectedByTheTrigger = insertEvent(databaseCutoff.plus(Duration.ofDays(5)));
        UUID old = insertEvent(databaseCutoff.minus(Duration.ofDays(5)));
        Set<UUID> before = purgedEventIds();

        AuditDailyJob.RunResult result = runPurge();

        assertThat(result.result()).isEqualTo(AuditJobResult.COMPLETED);
        assertThat(result.cutoff()).isEqualTo(databaseCutoff);
        assertThat(eventExists(protectedByTheTrigger)).isTrue();
        assertThat(eventExists(old)).isFalse();
        JsonNode event = purgedEventsSince(before).getFirst();
        assertThat(event.get("details").get("cutoff").asText()).isEqualTo(databaseCutoff.toString());
        assertThat(jdbc.sql("select last_cutoff from audit_job_run where job_name = 'AUDIT_RETENTION_PURGE'")
                        .query(java.sql.Timestamp.class)
                        .single()
                        .toInstant())
                .isEqualTo(databaseCutoff);
    }

    @Test
    void aJvmClockBehindTheDatabaseKeepsThePolicyCutoff() {
        java.time.Instant databaseNow = jdbc.sql("select now()")
                .query(java.sql.Timestamp.class)
                .single()
                .toInstant();
        clock.set(databaseNow.minus(Duration.ofDays(20)));
        java.time.Instant policyCutoff = databaseNow
                .minus(Duration.ofDays(20))
                .atZone(java.time.ZoneOffset.UTC)
                .toLocalDate()
                .atStartOfDay(java.time.ZoneOffset.UTC)
                .minusMonths(12)
                .toInstant();
        UUID betweenTheCutoffs = insertEvent(policyCutoff.plus(Duration.ofDays(5)));

        AuditDailyJob.RunResult result = runPurge();

        assertThat(result.result()).isEqualTo(AuditJobResult.COMPLETED);
        assertThat(result.cutoff()).isEqualTo(policyCutoff);
        assertThat(eventExists(betweenTheCutoffs)).isTrue();
    }

    private AuditDailyJob.RunResult runPurge() {
        return runner.runLocked(AuditJob.AUDIT_RETENTION_PURGE).orElseThrow();
    }
}
