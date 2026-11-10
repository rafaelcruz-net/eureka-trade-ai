package com.memetrade.audit.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.audit.domain.AuditJob;
import com.memetrade.testsupport.AdminSessions;
import com.memetrade.testsupport.SyntheticIds;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Metrics and logs of the AuditTrail (NFR9.2 to NFR9.4, observability-design.md minus the removed aggregation): every
 * metric is exposed, no metric label carries an address, actor, tenant, event id or detail value, and the logs of the
 * jobs, the query and the job failures carry their fields and correlation id but never content.
 */
@Tag("u2")
@ExtendWith(OutputCaptureExtension.class)
class AuditObservabilityIT extends AbstractAuditJobIT {

    private static final Set<String> ALLOWED_LABELS = Set.of("action", "reason", "job", "result", "le");
    private static final Pattern LABEL = Pattern.compile("([a-zA-Z_]+)=\"([^\"]*)\"");
    private static final Pattern UUID_SHAPE =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern BASE58_ADDRESS = Pattern.compile("[1-9A-HJ-NP-Za-km-z]{32,44}");
    private static final Pattern CORRELATION = Pattern.compile("\"correlationId\":\"([^\"]+)\"");

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AuditEventPublisher publisher;

    @Autowired
    private AuditOutboxRelay relay;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private AuditJobRunner runner;

    @Autowired
    private AuditJobRecovery recovery;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AdminSessions adminSessions;

    @Test
    void prometheusExposesEveryMetricOfTheTrailAndNoAggregationMetric() {
        publishPersonEvent(SyntheticIds.mint());
        relayAll();
        runner.runLocked(AuditJob.AUDIT_OUTBOX_CLEANUP).orElseThrow();

        String metrics = prometheus();

        assertThat(metrics)
                .contains("audit_outbox_pending")
                .contains("audit_outbox_oldest_age_seconds")
                .contains("audit_delivery_seconds_bucket")
                .contains("audit_delivery_unmeasured_total")
                .contains("audit_events_published_total{action=\"OBSERVABILITY_IT\"")
                .contains("audit_events_recorded_today")
                .contains("audit_publish_rejected_total{reason=\"FORBIDDEN_VALUE\"")
                .contains("audit_job_runs_total{job=\"AUDIT_OUTBOX_CLEANUP\",result=\"COMPLETED\"")
                .contains("audit_job_deleted_total{job=\"AUDIT_RETENTION_PURGE\"")
                .contains("audit_job_duration_seconds_bucket{job=\"AUDIT_OUTBOX_CLEANUP\"")
                .contains("audit_job_last_success_timestamp_seconds{job=\"AUDIT_OUTBOX_CLEANUP\"")
                .doesNotContain("audit_forbidden_aggregated");
    }

    @Test
    void noMetricOfTheTrailCarriesAnAddressActorTenantEventIdOrDetailValue() {
        String address = SyntheticIds.mint();
        UUID tenant = publishPersonEvent(address);
        relayAll();
        runner.runLocked(AuditJob.AUDIT_RETENTION_PURGE).orElseThrow();

        List<String> problems = new ArrayList<>();
        for (String line : prometheus().split("\n")) {
            if (!line.startsWith("audit_")) {
                continue;
            }
            Matcher label = LABEL.matcher(line);
            while (label.find()) {
                if (!ALLOWED_LABELS.contains(label.group(1))) {
                    problems.add("label " + label.group(1));
                }
                String value = label.group(2);
                if (UUID_SHAPE.matcher(value).find()
                        || BASE58_ADDRESS.matcher(value).find()) {
                    problems.add("value shape in label " + label.group(1));
                }
            }
            if (line.contains(address) || line.contains(tenant.toString())) {
                problems.add("published value in " + line.substring(0, line.indexOf('{') > 0 ? line.indexOf('{') : 10));
            }
        }

        assertThat(problems).isEmpty();
    }

    @Test
    void theJobLogsCarryJobCutoffAndTheirOwnCorrelationId(CapturedOutput output) {
        runner.runLocked(AuditJob.AUDIT_RETENTION_PURGE).orElseThrow();
        runner.runLocked(AuditJob.AUDIT_OUTBOX_CLEANUP).orElseThrow();

        String started = line(output, "Audit job started: job=AUDIT_RETENTION_PURGE, cutoff=" + CUTOFF);
        String finished = line(output, "Audit job finished: job=AUDIT_RETENTION_PURGE, cutoff=" + CUTOFF);
        String cleanup = line(output, "Audit job finished: job=AUDIT_OUTBOX_CLEANUP");

        assertThat(finished).contains("deleted=").contains("durationMs=").contains("result=COMPLETED");
        assertThat(correlation(started)).isEqualTo(correlation(finished));
        assertThat(correlation(cleanup)).isNotEqualTo(correlation(finished));
        assertThat(started).contains("\"level\":\"INFO\"");
    }

    @Test
    void aFailedJobLogsErrorWithTheTechnicalCauseOnly(CapturedOutput output) {
        connect(OWNER).sql("revoke delete on audit_event from audit_retention").update();
        try {
            runner.runLocked(AuditJob.AUDIT_RETENTION_PURGE).orElseThrow();
        } finally {
            connect(OWNER).sql("grant delete on audit_event to audit_retention").update();
        }

        String failed = line(output, "Audit job failed: job=AUDIT_RETENTION_PURGE");
        assertThat(failed)
                .contains("\"level\":\"ERROR\"")
                .contains("result=FAILED")
                .contains("SQLState 42501");
        assertThat(failed).doesNotContainIgnoringCase("permission denied").doesNotContain("delete from");
    }

    @Test
    void anOverdueJobLogsAWarningAtStartup(CapturedOutput output) {
        recovery.recoverOverdueJobs();

        assertThat(line(output, "Overdue audit job runs at startup: job=AUDIT_RETENTION_PURGE"))
                .contains("hoursSinceLastSuccess=never")
                .contains("\"level\":\"WARN\"");
    }

    @Test
    void theQueryLogNamesRouteStatusAndCountButNeverTheContent(CapturedOutput output) throws Exception {
        String address = SyntheticIds.mint();
        publishPersonEvent(address);
        relayAll();
        AdminSessions.AdminSession admin = adminSessions.openAdminSession();

        mvc.perform(get("/api/v1/admin/audit-events?from=" + clock.instant().minus(Duration.ofHours(1)) + "&to="
                                + clock.instant().plus(Duration.ofHours(1)))
                        .header("Authorization", admin.bearer()))
                .andReturn();

        String answered = line(output, "Audit trail query answered");
        assertThat(answered)
                .contains("route=GET /api/v1/admin/audit-events")
                .contains("status=200")
                .contains("items=");
        assertThat(output.getAll()).doesNotContain(address);
    }

    private UUID publishPersonEvent(String address) {
        UUID tenant = UUID.randomUUID();
        // u2-retention (BR2.1): the wallet goes by its own field; the trail keeps its HMAC only.
        tx.executeWithoutResult(status -> publisher.publish(AuditEventCommand.person(
                        tenant,
                        UUID.randomUUID(),
                        "OBSERVABILITY_IT",
                        null,
                        UUID.randomUUID().toString(),
                        clock.instant(),
                        Map.of("source", "observability-it"))
                .withWallet(address)));
        try {
            tx.executeWithoutResult(status -> publisher.publish(AuditEventCommand.person(
                    tenant,
                    UUID.randomUUID(),
                    "OBSERVABILITY_IT",
                    null,
                    UUID.randomUUID().toString(),
                    clock.instant(),
                    Map.of("note", "Bearer abcdefghijklmnop"))));
        } catch (IllegalArgumentException refused) {
            // Expected: counted under audit_publish_rejected_total{reason="FORBIDDEN_VALUE"}.
        }
        return tenant;
    }

    private void relayAll() {
        while (relay.relayBatch() > 0) {
            // deliver every pending row of this and earlier tests
        }
    }

    private String prometheus() {
        return rest.getForEntity("http://127.0.0.1:" + managementPort + "/actuator/prometheus", String.class)
                .getBody();
    }

    private static String line(CapturedOutput output, String fragment) {
        return output.getAll()
                .lines()
                .filter(candidate -> candidate.contains(fragment))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("no log line contains: " + fragment));
    }

    private static String correlation(String logLine) {
        Matcher matcher = CORRELATION.matcher(logLine);
        assertThat(matcher.find()).as("correlationId in " + logLine).isTrue();
        return matcher.group(1);
    }
}
