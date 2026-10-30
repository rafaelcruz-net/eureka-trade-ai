package com.memetrade.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.memetrade.audit.domain.AuditJob;
import com.memetrade.audit.infra.AuditJobRunner;
import com.memetrade.audit.infra.AuditOutboxRelay;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.AdminSessions;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.SensitiveValueScanner;
import com.memetrade.testsupport.SensitiveValueScanner.Finding;
import com.memetrade.testsupport.SensitiveValueScanner.Kind;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * NFR7.3 / F6 sweep: runs the login, refresh, rejection, list, guest refusal on the trail route, administrator query,
 * relay and retention purge flows, then scans the captured JSON logs of the API (U1), and since U2 also the logs of the
 * PostgreSQL container, the Prometheus output, {@code audit_outbox.payload} and {@code audit_event} for the values the
 * test itself generated (nonce, signature, access and refresh tokens, full wallet address), any JWT, the
 * {@code Authorization} header name and the {@code Bearer} prefix. The full address is allowed only in
 * {@code audit_event.details} (BR2.4). Findings report place and kind, never the value.
 */
@ExtendWith(OutputCaptureExtension.class)
class LogScanIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private AuditOutboxRelay relay;

    @Autowired
    private AuditJobRunner jobs;

    @Autowired
    private AdminSessions adminSessions;

    @Autowired
    private TestRestTemplate rest;

    @LocalManagementPort
    private int managementPort;

    @Test
    void logsNeverContainNonceSignatureTokensAuthorizationOrFullAddress(CapturedOutput output) throws Exception {
        AuthFlow auth = new AuthFlow(mvc, json);
        TestWallet wallet = TestWallet.generate();
        TestAccounts.betaUser(jdbc, wallet.address());

        JsonNode challenge = auth.challenge(wallet);
        String message = challenge.get("message").asText();
        String signature = wallet.sign(message);
        MockHttpServletResponse login = auth.verify(wallet, message, signature);
        String accessToken = auth.accessToken(login);
        String refreshToken = AuthFlow.refreshToken(login);
        MockHttpServletResponse replay = auth.verify(wallet, message, signature);
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("mt_refresh", refreshToken)));
        mvc.perform(get("/api/v1/scanner/tokens").header("Authorization", "Bearer " + accessToken));
        mvc.perform(get("/api/v1/scanner/tokens").header("Authorization", "Bearer " + signature));

        assertThat(replay.getStatus()).isEqualTo(401);
        String logs = output.getAll();
        assertThat(logs)
                .doesNotContain(challenge.get("nonce").asText())
                .doesNotContain(signature)
                .doesNotContain(accessToken)
                .doesNotContain(refreshToken)
                .doesNotContain(wallet.address())
                .doesNotContain("Bearer ");
    }

    @Test
    @Tag("u2")
    void theDatabaseLogsMetricsAndAuditTablesNeverHoldSecretsAndTheAddressOnlyInDetails(CapturedOutput output)
            throws Exception {
        SensitiveValueScanner scanner = new SensitiveValueScanner();
        AuthFlow auth = new AuthFlow(mvc, json);
        TestWallet wallet = TestWallet.generate();
        TestAccounts.betaUser(jdbc, wallet.address());
        scanner.register(Kind.WALLET_ADDRESS, wallet.address());

        JsonNode challenge = auth.challenge(wallet);
        String message = challenge.get("message").asText();
        String signature = wallet.sign(message);
        scanner.register(Kind.NONCE, challenge.get("nonce").asText()).register(Kind.SIGNATURE, signature);
        MockHttpServletResponse login = auth.verify(wallet, message, signature);
        String accessToken = auth.accessToken(login);
        String refreshToken = AuthFlow.refreshToken(login);
        scanner.register(Kind.ACCESS_TOKEN, accessToken).register(Kind.REFRESH_TOKEN, refreshToken);
        auth.verify(wallet, message, signature); // replay: LOGIN_REJECTED with the full address in details
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("mt_refresh", refreshToken)));
        mvc.perform(get("/api/v1/admin/audit-events?from=2026-10-01T00:00:00Z&to=2026-10-02T00:00:00Z")
                .header("Authorization", "Bearer " + accessToken));
        AdminSessions.AdminSession admin = adminSessions.openAdminSession();
        scanner.register(Kind.ACCESS_TOKEN, admin.token());
        mvc.perform(get("/api/v1/admin/audit-events?from=" + clock.instant().minus(Duration.ofHours(1)) + "&to="
                        + clock.instant().plus(Duration.ofHours(1)))
                .header("Authorization", admin.bearer()));
        while (relay.relayBatch() > 0) {
            // deliver everything, including the events of this flow
        }
        jdbc.sql("update shedlock set lock_until = now() - interval '1 second'").update();
        jobs.runLocked(AuditJob.AUDIT_RETENTION_PURGE);

        List<Finding> findings = new ArrayList<>();
        findings.addAll(scanner.scan("api logs", output.getAll()));
        findings.addAll(scanner.scan("postgresql logs", POSTGRES.getLogs()));
        findings.addAll(scanner.scan("prometheus", prometheus()));
        scanOutbox(scanner, findings);
        scanTrail(scanner, findings);

        assertThat(findings).as(SensitiveValueScanner.report(findings)).isEmpty();
        assertThat(jdbc.sql("select count(*) from audit_event where details->>'walletAddress' = :a")
                        .param("a", wallet.address())
                        .query(Long.class)
                        .single())
                .as("the full address of the LOGIN events is kept in details, where it is allowed")
                .isPositive();
    }

    /** {@code payload} without its {@code details} (no address allowed) and the details (address allowed). */
    private void scanOutbox(SensitiveValueScanner scanner, List<Finding> findings) throws Exception {
        for (String payload : jdbc.sql("select payload::text from audit_outbox")
                .query(String.class)
                .list()) {
            ObjectNode node = (ObjectNode) json.readTree(payload);
            JsonNode details = node.remove("details");
            findings.addAll(scanner.scan("audit_outbox.payload", node.toString()));
            findings.addAll(scanner.scan("audit_outbox.payload.details", String.valueOf(details), true));
        }
    }

    /** {@code details} may hold the full address; every other column may not. */
    private void scanTrail(SensitiveValueScanner scanner, List<Finding> findings) {
        List<Map<String, Object>> rows = jdbc.sql("""
                        select actor, coalesce(target_ref, '') as target_ref, correlation_id, details::text as details
                        from audit_event
                        """).query().listOfRows();
        for (Map<String, Object> row : rows) {
            findings.addAll(scanner.scan(
                    "audit_event.actor/target_ref/correlation_id",
                    row.get("actor") + " " + row.get("target_ref") + " " + row.get("correlation_id")));
            findings.addAll(scanner.scan("audit_event.details", String.valueOf(row.get("details")), true));
        }
    }

    private String prometheus() {
        return rest.getForEntity("http://127.0.0.1:" + managementPort + "/actuator/prometheus", String.class)
                .getBody();
    }
}
