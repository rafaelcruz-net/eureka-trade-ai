package com.memetrade.audit.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.audit.domain.AuditJob;
import com.memetrade.audit.domain.AuditJobResult;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.ResponseEntity;

/**
 * Startup recovery and health of the daily jobs (NFR4.5, NFR9.1) with the injectable clock: 23 h and 25 h since the
 * last success (runs at startup or not), 25 h and 27 h (healthy or degraded), a database without a row (runs and is
 * degraded until the first success), and readiness unchanged while a job is degraded.
 */
@Tag("u2")
class AuditJobRecoveryIT extends AbstractAuditJobIT {

    @Autowired
    private AuditJobRecovery recovery;

    @Autowired
    private JdbcAuditJobRunStore store;

    @Autowired
    private TestRestTemplate rest;

    @LocalManagementPort
    private int managementPort;

    @Test
    void withoutRowsBothJobsAreDegradedRunAtStartupAndBecomeHealthy() throws Exception {
        assertThat(component("auditRetention").get("status").asText()).isEqualTo("DEGRADED");
        assertThat(component("auditRetention").at("/details/lastResult").asText())
                .isEqualTo("NEVER_RUN");
        assertThat(component("auditOutboxCleanup").get("status").asText()).isEqualTo("DEGRADED");

        assertThat(recovery.recoverOverdueJobs())
                .containsExactly(AuditJob.AUDIT_RETENTION_PURGE, AuditJob.AUDIT_OUTBOX_CLEANUP);

        assertThat(lastResult(AuditJob.AUDIT_RETENTION_PURGE)).isEqualTo("COMPLETED");
        assertThat(lastResult(AuditJob.AUDIT_OUTBOX_CLEANUP)).isEqualTo("COMPLETED");
        assertThat(component("auditRetention").get("status").asText()).isEqualTo("UP");
        assertThat(component("auditOutboxCleanup").get("status").asText()).isEqualTo("UP");
    }

    @Test
    void aSuccessTwentyThreeHoursAgoDoesNotRunAtStartup() {
        completedHoursAgo(AuditJob.AUDIT_RETENTION_PURGE, 23);
        completedHoursAgo(AuditJob.AUDIT_OUTBOX_CLEANUP, 23);

        assertThat(recovery.recoverOverdueJobs()).isEmpty();
        assertThat(heldLocks()).isZero();
    }

    @Test
    void aSuccessTwentyFiveHoursAgoRunsAtStartup() {
        completedHoursAgo(AuditJob.AUDIT_RETENTION_PURGE, 25);
        completedHoursAgo(AuditJob.AUDIT_OUTBOX_CLEANUP, 23);

        assertThat(recovery.recoverOverdueJobs()).containsExactly(AuditJob.AUDIT_RETENTION_PURGE);
        assertThat(store.find(AuditJob.AUDIT_RETENTION_PURGE).orElseThrow().lastSuccessAt())
                .contains(clock.instant());
    }

    @Test
    void healthIsUpTwentyFiveHoursAfterASuccessAndDegradedAtTwentySeven() throws Exception {
        completedHoursAgo(AuditJob.AUDIT_RETENTION_PURGE, 0);
        completedHoursAgo(AuditJob.AUDIT_OUTBOX_CLEANUP, 0);

        clock.advance(Duration.ofHours(25));
        assertThat(component("auditRetention").get("status").asText()).isEqualTo("UP");
        assertThat(component("auditRetention")
                        .at("/details/hoursSinceLastSuccess")
                        .asLong())
                .isEqualTo(25);

        clock.advance(Duration.ofHours(2));
        assertThat(component("auditRetention").get("status").asText()).isEqualTo("DEGRADED");
        assertThat(component("auditOutboxCleanup").get("status").asText()).isEqualTo("DEGRADED");
    }

    @Test
    void aFailedLastRunIsDegradedAndTheHealthStillAnswers200WithReadinessUp() throws Exception {
        Instant success = clock.instant().minus(Duration.ofHours(1));
        store.record(
                AuditJob.AUDIT_RETENTION_PURGE, success.minusSeconds(5), AuditJobResult.COMPLETED, success, CUTOFF);
        store.record(AuditJob.AUDIT_RETENTION_PURGE, clock.instant(), AuditJobResult.FAILED, clock.instant(), CUTOFF);

        ResponseEntity<String> health = get("/actuator/health");
        JsonNode retention = json.readTree(health.getBody()).at("/components/auditRetention");

        assertThat(health.getStatusCode().value()).isEqualTo(200);
        assertThat(retention.get("status").asText()).isEqualTo("DEGRADED");
        assertThat(retention.at("/details/lastResult").asText()).isEqualTo("FAILED");
        ResponseEntity<String> readiness = get("/actuator/health/readiness");
        assertThat(readiness.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(readiness.getBody()).get("status").asText()).isEqualTo("UP");
        assertThat(readiness.getBody()).doesNotContain("auditRetention").doesNotContain("auditOutboxCleanup");
    }

    @Test
    void aTimeLimitedLastRunIsDegraded() throws Exception {
        store.record(
                AuditJob.AUDIT_OUTBOX_CLEANUP,
                clock.instant(),
                AuditJobResult.TIME_LIMIT,
                clock.instant(),
                clock.instant().minus(Duration.ofDays(7)));

        assertThat(component("auditOutboxCleanup").get("status").asText()).isEqualTo("DEGRADED");
        assertThat(component("auditOutboxCleanup").at("/details/lastResult").asText())
                .isEqualTo("TIME_LIMIT");
    }

    private void completedHoursAgo(AuditJob job, int hours) {
        Instant success = clock.instant().minus(Duration.ofHours(hours));
        store.record(job, success.minusSeconds(10), AuditJobResult.COMPLETED, success, CUTOFF);
    }

    private JsonNode component(String name) throws Exception {
        return json.readTree(get("/actuator/health").getBody()).at("/components/" + name);
    }

    private ResponseEntity<String> get(String path) {
        return rest.getForEntity("http://127.0.0.1:" + managementPort + path, String.class);
    }
}
