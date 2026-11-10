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
        auth.verify(wallet, message, signature); // replay: LOGIN_REJECTED with the masked actor and the HMAC only
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
        // u2-retention (BR2.1, AC3.2.1): the LOGIN events keep only the HMAC of the wallet, never the address.
        assertThat(jdbc.sql("select count(*) from audit_event where wallet_hmac = :h")
                        .param("h", com.memetrade.testsupport.TestWalletHmac.of(wallet.address()))
                        .query(Long.class)
                        .single())
                .as("the LOGIN events of the wallet are found by its HMAC")
                .isPositive();
        assertThat(jdbc.sql("select count(*) from audit_event where strpos(details::text, :a) > 0")
                        .param("a", wallet.address())
                        .query(Long.class)
                        .single())
                .as("no event keeps the full address in details")
                .isZero();
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

    /**
     * u4-data-requests (BR4.3; NFR1.1, NFR9.1): a request created by a guest, one registered from an e-mail with its
     * contact, a verification started, refused with another wallet and confirmed, the transitions with a response and
     * the release of the summary. The text of the requests, the contact, the verification message and both signatures,
     * the tokens and the full wallet never appear in the api logs, the PostgreSQL logs, the Prometheus output, the
     * outbox, the trail or the idempotency records of the requests.
     */
    @Test
    @Tag("u4-data-requests")
    void theDataRequestFlowsNeverLeakTheTextTheContactTheMessageOrTheSignature(CapturedOutput output) throws Exception {
        SensitiveValueScanner scanner = new SensitiveValueScanner();
        com.memetrade.testsupport.DataRequestCalls calls = new com.memetrade.testsupport.DataRequestCalls(mvc, json);
        AuthFlow auth = new AuthFlow(mvc, json);
        TestWallet wallet = TestWallet.generate();
        TestAccounts.Seeded person = TestAccounts.betaUser(jdbc, wallet.address());
        scanner.register(Kind.WALLET_ADDRESS, wallet.address());
        String accessToken = auth.accessToken(auth.login(wallet));
        scanner.register(Kind.ACCESS_TOKEN, accessToken);
        AdminSessions.AdminSession admin = adminSessions.openAdminSession();
        scanner.register(Kind.ACCESS_TOKEN, admin.token());
        String text = "texto-do-pedido-" + java.util.UUID.randomUUID();
        String emailText = "texto-do-email-" + java.util.UUID.randomUUID();
        String contact = "contato-" + java.util.UUID.randomUUID() + "@example.org";
        String response = "resposta-do-admin-" + java.util.UUID.randomUUID();
        scanner.register(Kind.REQUEST_TEXT, text)
                .register(Kind.REQUEST_TEXT, emailText)
                .register(Kind.REQUEST_TEXT, response)
                .register(Kind.CONTACT, contact);

        String access = json.readTree(
                        calls.create("Bearer " + accessToken, "ACCESS", text).getContentAsString())
                .get("requestId")
                .asText();
        String email = json.readTree(calls.register(
                                admin.bearer(),
                                "DELETION",
                                emailText,
                                contact,
                                clock.instant().toString(),
                                person.userId())
                        .getContentAsString())
                .get("requestId")
                .asText();
        String message = json.readTree(
                        calls.startVerification(admin.bearer(), email).getContentAsString())
                .get("message")
                .asText();
        String wrongSignature = TestWallet.generate().sign(message);
        String signature = wallet.sign(message);
        scanner.register(Kind.REQUEST_TEXT, message)
                .register(Kind.SIGNATURE, wrongSignature)
                .register(Kind.SIGNATURE, signature);
        assertThat(calls.confirm(admin.bearer(), email, wrongSignature).getStatus())
                .isEqualTo(422);
        assertThat(calls.confirm(admin.bearer(), email, signature).getStatus()).isEqualTo(200);
        calls.transition(admin.bearer(), access, "IN_PROGRESS", null, null, false);
        calls.transition(admin.bearer(), access, "DONE", response, null, true);
        calls.adminList(admin.bearer(), null);
        calls.list("Bearer " + accessToken);
        while (relay.relayBatch() > 0) {
            // deliver every event of this flow to the trail
        }

        List<Finding> findings = new ArrayList<>();
        findings.addAll(scanner.scan("api logs", output.getAll()));
        findings.addAll(scanner.scan("postgresql logs", POSTGRES.getLogs()));
        findings.addAll(scanner.scan("prometheus", prometheus()));
        scanOutbox(scanner, findings);
        scanTrail(scanner, findings);
        for (String body : jdbc.sql("""
                        select coalesce(response_body::text, '') from idempotency_record
                        where scope like '%:privacy.request.%'
                        """).query(String.class).list()) {
            findings.addAll(scanner.scan("idempotency_record.response_body", body));
        }

        assertThat(findings).as(SensitiveValueScanner.report(findings)).isEmpty();
        assertThat(jdbc.sql("select count(*) from audit_event where details->>'requestId' in (:a, :e)")
                        .param("a", access)
                        .param("e", email)
                        .query(Long.class)
                        .single())
                .as("the events of the flow reached the trail and were scanned")
                .isPositive();
    }

    /**
     * u5-anonymization (NFR1.1, NFR1.2; BR5.4): a DELETION request of a guest anonymized through the administrator route.
     * The full address of the anonymized test wallet (the needle) and the tokens never appear in the api logs, the
     * PostgreSQL logs, the Prometheus output, the outbox, the trail or the idempotency records of the requests.
     */
    @Test
    @Tag("u5-anonymization")
    void theAnonymizationNeverLeaksTheAddressOfTheAnonymizedWallet(CapturedOutput output) throws Exception {
        SensitiveValueScanner scanner = new SensitiveValueScanner();
        com.memetrade.testsupport.DataRequestCalls calls = new com.memetrade.testsupport.DataRequestCalls(mvc, json);
        AuthFlow auth = new AuthFlow(mvc, json);
        TestWallet wallet = TestWallet.generate();
        TestAccounts.betaUser(jdbc, wallet.address());
        scanner.register(Kind.WALLET_ADDRESS, wallet.address());
        String accessToken = auth.accessToken(auth.login(wallet));
        scanner.register(Kind.ACCESS_TOKEN, accessToken);
        AdminSessions.AdminSession admin = adminSessions.openAdminSession();
        scanner.register(Kind.ACCESS_TOKEN, admin.token());
        String id = json.readTree(calls.create("Bearer " + accessToken, "DELETION", "pedido-sintetico")
                        .getContentAsString())
                .get("requestId")
                .asText();
        assertThat(calls.transition(admin.bearer(), id, "IN_PROGRESS", null, null, false)
                        .getStatus())
                .isEqualTo(200);

        MockHttpServletResponse anonymized =
                calls.anonymize(admin.bearer(), id, "ANONIMIZAR", java.util.UUID.randomUUID());
        assertThat(anonymized.getStatus()).as(anonymized.getContentAsString()).isEqualTo(200);
        while (relay.relayBatch() > 0) {
            // deliver every event of this flow to the trail
        }

        List<Finding> findings = new ArrayList<>();
        findings.addAll(scanner.scan("api logs", output.getAll()));
        findings.addAll(scanner.scan("postgresql logs", POSTGRES.getLogs()));
        findings.addAll(scanner.scan("prometheus", prometheus()));
        findings.addAll(scanner.scan("anonymization answer", anonymized.getContentAsString()));
        scanOutbox(scanner, findings);
        scanTrail(scanner, findings);
        for (String body : jdbc.sql("""
                        select coalesce(response_body::text, '') from idempotency_record
                        where scope like '%:privacy.request.%'
                        """).query(String.class).list()) {
            findings.addAll(scanner.scan("idempotency_record.response_body", body));
        }

        assertThat(findings).as(SensitiveValueScanner.report(findings)).isEmpty();
        assertThat(jdbc.sql("select count(*) from audit_event where action = 'ACCOUNT_ANONYMIZED' and target_ref = :t")
                        .param(
                                "t",
                                "user:"
                                        + json.readTree(anonymized.getContentAsString())
                                                .get("userId")
                                                .asText())
                        .query(Long.class)
                        .single())
                .as("the event of the anonymization reached the trail and was scanned")
                .isEqualTo(1);
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
            // u2-retention (BR2.3): details may no longer hold the full address either.
            findings.addAll(scanner.scan("audit_outbox.payload.details", String.valueOf(details)));
        }
    }

    /** Since u2-retention (BR2.1, BR2.3) no column of the trail may hold the full address, details included. */
    private void scanTrail(SensitiveValueScanner scanner, List<Finding> findings) {
        List<Map<String, Object>> rows = jdbc.sql("""
                        select actor, coalesce(target_ref, '') as target_ref, correlation_id, details::text as details
                        from audit_event
                        """).query().listOfRows();
        for (Map<String, Object> row : rows) {
            findings.addAll(scanner.scan(
                    "audit_event.actor/target_ref/correlation_id",
                    row.get("actor") + " " + row.get("target_ref") + " " + row.get("correlation_id")));
            findings.addAll(scanner.scan("audit_event.details", String.valueOf(row.get("details"))));
        }
    }

    private String prometheus() {
        return rest.getForEntity("http://127.0.0.1:" + managementPort + "/actuator/prometheus", String.class)
                .getBody();
    }
}
