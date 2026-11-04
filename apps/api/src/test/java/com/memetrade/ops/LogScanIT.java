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

    /**
     * U4 (NFR9.6 to NFR9.8, NFR7.1): collection with a failing source, recalculation, filtered list and detail log the
     * source, the category and the exception class only: never the session token, the wallet, a mint or the text of
     * the source failure.
     */
    @Test
    @Tag("u4")
    void theU4FlowsLogNoTokenWalletMintOrFailureText() throws Exception {
        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            runU4Flows(appender);
        } finally {
            root.detachAppender(appender);
        }
    }

    private void runU4Flows(ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender)
            throws Exception {
        AuthFlow auth = new AuthFlow(mvc, json);
        TestWallet wallet = TestWallet.generate();
        TestAccounts.betaUser(jdbc, wallet.address());
        String accessToken = auth.accessToken(auth.login(wallet));
        collector.collectNow("sim-a");
        recalculator.recalculateNow();
        String mint = jdbc.sql("select mint from market_observation where source_id = 'sim-a' limit 1")
                .query(String.class)
                .single();
        mvc.perform(get("/api/v1/scanner/tokens?minHolders=10").header("Authorization", "Bearer " + accessToken));
        mvc.perform(
                get("/api/v1/tokens/solana/" + mint + "/assessment").header("Authorization", "Bearer " + accessToken));
        com.memetrade.testsupport.ScriptedMarketSource failing = new com.memetrade.testsupport.ScriptedMarketSource(
                        com.memetrade.testsupport.FixtureMarketSource.load("sim-a")
                                .as("log-scan-src"))
                .thenThrow(new IllegalStateException("upstream said SECRET-DETAIL-42"))
                .thenThrow(new IllegalStateException("upstream said SECRET-DETAIL-42"))
                .thenThrow(new IllegalStateException("upstream said SECRET-DETAIL-42"));
        store.registerSource(failing.source(), Duration.ofSeconds(30));
        new com.memetrade.marketdata.domain.MarketCollector(
                        store,
                        new com.memetrade.marketdata.infra.SourceCaller(
                                java.util.concurrent.Executors.newSingleThreadExecutor(),
                                d -> {},
                                new java.util.Random(1),
                                Duration.ofSeconds(1),
                                (s, n) -> {}),
                        com.memetrade.marketdata.domain.UnitOfWork.DIRECT,
                        clock)
                .collect(failing);

        String logs = appender.list.stream()
                .map(event -> event.getFormattedMessage() + " " + event.getMDCPropertyMap())
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(logs)
                .doesNotContain(accessToken)
                .doesNotContain(wallet.address())
                .doesNotContain(mint)
                .doesNotContain("SECRET-DETAIL-42");
        // The positive check (what the failure log does contain) is MarketCollectorJobTest: in the full integration
        // run the logging context may be stopped by another test context closing, so this sweep can see no event.
    }

    /**
     * U3 (NFR6.4, NFR7.1, NFR9.5, BR2.3): issue, look up (valid and malformed), create the account, open the Account
     * screen and revoke; the invitation code, the full address, the nonce, the signature and the tokens never appear in
     * the api logs, the PostgreSQL logs, the Prometheus output, the outbox, the trail (code not even in details) or the
     * idempotency records.
     */
    @Test
    @Tag("u3")
    void theU3FlowsNeverLeakTheInvitationCodeTheFullAddressOrASecret(CapturedOutput output) throws Exception {
        SensitiveValueScanner scanner = new SensitiveValueScanner();
        AuthFlow auth = new AuthFlow(mvc, json);
        AdminSessions.AdminSession admin = adminSessions.openAdminSession();
        scanner.register(Kind.ACCESS_TOKEN, admin.token());
        MockHttpServletResponse issued = mvc.perform(post("/api/v1/admin/invites")
                        .header("Authorization", admin.bearer())
                        .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"" + com.memetrade.testsupport.TestInvites.BETA_TENANT
                                + "\",\"expiresAt\":\"" + clock.instant().plus(Duration.ofDays(1)) + "\"}"))
                .andReturn()
                .getResponse();
        String code = json.readTree(issued.getContentAsString()).get("code").asText();
        scanner.register(Kind.INVITE_CODE, code);
        TestWallet wallet = TestWallet.generate();
        scanner.register(Kind.WALLET_ADDRESS, wallet.address());

        mvc.perform(get("/api/v1/invites/" + code));
        mvc.perform(get("/api/v1/invites/" + code.substring(1)));
        JsonNode challenge = auth.challenge(wallet);
        String message = challenge.get("message").asText();
        String signature = wallet.sign(message);
        scanner.register(Kind.NONCE, challenge.get("nonce").asText()).register(Kind.SIGNATURE, signature);
        MockHttpServletResponse created = auth.verifyWithInvite(wallet, message, signature, code, "v1");
        String accessToken = auth.accessToken(created);
        scanner.register(Kind.ACCESS_TOKEN, accessToken).register(Kind.REFRESH_TOKEN, AuthFlow.refreshToken(created));
        auth.verifyWithInvite(wallet, message, signature, code, "v1"); // replay: LOGIN_REJECTED
        mvc.perform(get("/api/v1/account").header("Authorization", "Bearer " + accessToken));
        String inviteId =
                json.readTree(issued.getContentAsString()).get("inviteId").asText();
        mvc.perform(post("/api/v1/admin/invites/" + inviteId + "/revoke")
                .header("Authorization", admin.bearer())
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString()));
        while (relay.relayBatch() > 0) {
            // deliver every event of this flow to the trail
        }

        List<Finding> findings = new ArrayList<>();
        findings.addAll(scanner.scan("api logs", output.getAll()));
        findings.addAll(scanner.scan("postgresql logs", POSTGRES.getLogs()));
        findings.addAll(scanner.scan("prometheus", prometheus()));
        scanOutbox(scanner, findings);
        scanTrail(scanner, findings);
        // The U3 operations only: the U1 challenge record keeps the challenge it replays by design (BR1.10 of U1).
        for (String body : jdbc.sql("""
                        select coalesce(response_body::text, '') from idempotency_record
                        where scope like '%:admin.invite.%'
                        """).query(String.class).list()) {
            findings.addAll(scanner.scan("idempotency_record.response_body", body));
        }

        assertThat(created.getStatus()).isEqualTo(200);
        assertThat(jdbc.sql("select count(*) from idempotency_record where scope like '%:admin.invite.%'")
                        .query(Long.class)
                        .single())
                .as("the issuance and the revocation records were scanned")
                .isGreaterThanOrEqualTo(2);
        assertThat(findings).as(SensitiveValueScanner.report(findings)).isEmpty();
    }

    @Autowired
    private com.memetrade.marketdata.infra.MarketCollectorJob collector;

    @Autowired
    private com.memetrade.riskassessment.infra.RiskRecalculator recalculator;

    @Autowired
    private com.memetrade.marketdata.domain.MarketStore store;

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
