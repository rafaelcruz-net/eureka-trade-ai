package com.memetrade.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** NFR4.5 and NFR9.1: startup recovery after 24 h without success and DEGRADED health after 26 h. */
@Tag("u2")
class AuditJobPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");

    private final AuditJobPolicy policy = new AuditJobPolicy(Duration.ofHours(24), Duration.ofHours(26));

    @Test
    void aJobWithoutARowRunsAtStartupAndIsDegraded() {
        assertThat(policy.shouldRunAtStartup(Optional.empty(), NOW)).isTrue();
        assertThat(policy.isHealthy(Optional.empty(), NOW)).isFalse();
    }

    @Test
    void aSuccessTwentyThreeHoursAgoDoesNotRunAtStartup() {
        assertThat(policy.shouldRunAtStartup(completedHoursAgo(23), NOW)).isFalse();
    }

    @Test
    void aSuccessTwentyFiveHoursAgoRunsAtStartup() {
        assertThat(policy.shouldRunAtStartup(completedHoursAgo(25), NOW)).isTrue();
    }

    @Test
    void aSuccessTwentyFiveHoursAgoIsHealthy() {
        assertThat(policy.isHealthy(completedHoursAgo(25), NOW)).isTrue();
    }

    @Test
    void aSuccessTwentySevenHoursAgoIsDegraded() {
        assertThat(policy.isHealthy(completedHoursAgo(27), NOW)).isFalse();
    }

    @Test
    void exactlyTwentySixHoursIsStillHealthy() {
        assertThat(policy.isHealthy(completedHoursAgo(26), NOW)).isTrue();
    }

    @Test
    void aFailedOrStoppedLastRunIsDegradedEvenWithARecentSuccess() {
        Instant success = NOW.minus(Duration.ofHours(2));
        for (AuditJobResult result : new AuditJobResult[] {AuditJobResult.FAILED, AuditJobResult.TIME_LIMIT}) {
            Optional<AuditJobRun> run = Optional.of(new AuditJobRun(
                    AuditJob.AUDIT_RETENTION_PURGE,
                    NOW.minusSeconds(60),
                    result,
                    Optional.of(success),
                    Optional.empty()));

            assertThat(policy.isHealthy(run, NOW)).as(result.name()).isFalse();
            assertThat(policy.shouldRunAtStartup(run, NOW)).as(result.name()).isFalse();
        }
    }

    @Test
    void aRowThatNeverSucceededRunsAtStartupAndIsDegraded() {
        Optional<AuditJobRun> run = Optional.of(new AuditJobRun(
                AuditJob.AUDIT_OUTBOX_CLEANUP,
                NOW.minusSeconds(60),
                AuditJobResult.FAILED,
                Optional.empty(),
                Optional.empty()));

        assertThat(policy.shouldRunAtStartup(run, NOW)).isTrue();
        assertThat(policy.isHealthy(run, NOW)).isFalse();
        assertThat(AuditJobPolicy.sinceLastSuccess(run, NOW)).isEmpty();
    }

    private static Optional<AuditJobRun> completedHoursAgo(int hours) {
        Instant success = NOW.minus(Duration.ofHours(hours));
        return Optional.of(new AuditJobRun(
                AuditJob.AUDIT_RETENTION_PURGE,
                success.minusSeconds(30),
                AuditJobResult.COMPLETED,
                Optional.of(success),
                Optional.of(Instant.parse("2025-10-27T00:00:00Z"))));
    }
}
