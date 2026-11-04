package com.memetrade.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.marketdata.infra.MarketCollectorJob;
import com.memetrade.marketdata.infra.MarketHistoryCleanup;
import com.memetrade.riskassessment.infra.RiskRecalculator;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.web.servlet.MockMvc;

/**
 * NFR9.5: after collection, recalculation, cleanup, list and detail, every U4 metric carries labels from closed lists
 * only ({@code source}, {@code result}, {@code reason}, {@code route}, {@code status}, {@code band}) and no label value
 * looks like a mint, a chain-qualified token, a wallet, a person or a tenant; the HTTP timer keeps the route template.
 */
@Tag("u4")
class MetricsLabelsIT extends AbstractPostgresIT {

    private static final Set<String> ALLOWED = Set.of("source", "result", "reason", "route", "status", "band");
    private static final Pattern LINE = Pattern.compile("^(market_\\w+|risk_\\w+|scanner_\\w+)\\{([^}]*)\\}");
    private static final Pattern LABEL = Pattern.compile("(\\w+)=\"([^\"]*)\"");
    private static final Pattern BASE58_ID = Pattern.compile("[1-9A-HJ-NP-Za-km-z]{32,44}");
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private MarketCollectorJob collector;

    @Autowired
    private RiskRecalculator recalculator;

    @Autowired
    private MarketHistoryCleanup cleanup;

    @Autowired
    private TestRestTemplate rest;

    @LocalManagementPort
    private int managementPort;

    @Test
    void everyU4MetricUsesClosedLabelsWithoutPersonalOrTokenValues() throws Exception {
        collector.collectNow("sim-a");
        collector.collectNow("sim-b");
        recalculator.recalculateNow();
        cleanup.runLocked();
        TestWallet wallet = TestWallet.generate();
        TestAccounts.betaUser(jdbc, wallet.address());
        AuthFlow auth = new AuthFlow(mvc, json);
        String token = auth.accessToken(auth.login(wallet));
        String mint = jdbc.sql("select mint from market_observation where source_id = 'sim-a' limit 1")
                .query(String.class)
                .single();
        mvc.perform(get("/api/v1/scanner/tokens?minLiquidity=1").header("Authorization", "Bearer " + token));
        mvc.perform(get("/api/v1/tokens/solana/" + mint + "/assessment").header("Authorization", "Bearer " + token));

        String metrics = rest.getForEntity("http://127.0.0.1:" + managementPort + "/actuator/prometheus", String.class)
                .getBody();

        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (String line : metrics.split("\n")) {
            Matcher metric = LINE.matcher(line);
            if (metric.find()) {
                checked++;
                Matcher label = LABEL.matcher(metric.group(2));
                while (label.find()) {
                    check(metric.group(1), label.group(1), label.group(2), problems);
                }
            }
            if (line.startsWith("http_server_requests_seconds") && line.contains("/api/v1/tokens/")) {
                assertThat(line)
                        .contains("uri=\"/api/v1/tokens/{chain}/{mint}/assessment\"")
                        .doesNotContain(mint);
            }
        }

        assertThat(checked).as("U4 metrics exported").isGreaterThan(10);
        assertThat(metrics)
                .contains("market_collection_duration_seconds")
                .contains("risk_recalculation_total")
                .contains("market_history_cleanup_deleted_total")
                .contains("scanner_excluded_by_unknown")
                .doesNotContain(mint)
                .doesNotContain(wallet.address());
        assertThat(problems).isEmpty();
    }

    /**
     * U3 (NFR9.2, NFR9.3): after a complete U3 flow the HTTP timer carries the route TEMPLATE and the status only;
     * no label holds the invitation code, an inviteId, the wallet or a person.
     */
    @Test
    @org.junit.jupiter.api.Tag("u3")
    void theU3RoutesAreMeasuredByTemplateWithoutCodeInviteIdOrWallet() throws Exception {
        com.memetrade.testsupport.AdminSessions.AdminSession admin = adminSessions.openAdminSession();
        String issued = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/api/v1/admin/invites")
                        .header("Authorization", admin.bearer())
                        .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"" + com.memetrade.testsupport.TestInvites.BETA_TENANT
                                + "\",\"expiresAt\":\"" + clock.instant().plus(java.time.Duration.ofDays(1)) + "\"}"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String code = json.readTree(issued).get("code").asText();
        String inviteId = json.readTree(issued).get("inviteId").asText();
        TestWallet wallet = TestWallet.generate();
        AuthFlow auth = new AuthFlow(mvc, json);
        mvc.perform(get("/api/v1/invites/" + code));
        mvc.perform(get("/api/v1/invites/" + com.memetrade.testsupport.TestInvites.newCode()));
        String token = auth.accessToken(auth.createAccount(wallet, code));
        mvc.perform(get("/api/v1/account").header("Authorization", "Bearer " + token));
        mvc.perform(get("/api/v1/admin/invites").header("Authorization", admin.bearer()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/v1/admin/invites/" + inviteId + "/revoke")
                .header("Authorization", admin.bearer())
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString()));

        String metrics = rest.getForEntity("http://127.0.0.1:" + managementPort + "/actuator/prometheus", String.class)
                .getBody();

        java.util.Set<String> uris = new java.util.TreeSet<>();
        Matcher uri = Pattern.compile("^http_server_requests_seconds_count\\{[^}]*uri=\"([^\"]*)\"", Pattern.MULTILINE)
                .matcher(metrics);
        while (uri.find()) {
            uris.add(uri.group(1));
        }
        assertThat(uris)
                .contains(
                        "/api/v1/invites/{code}",
                        "/api/v1/account",
                        "/api/v1/admin/invites",
                        "/api/v1/admin/invites/{inviteId}/revoke",
                        "/api/v1/auth/verify");
        assertThat(uris)
                .noneMatch(value -> UUID_TEXT.matcher(value).find()
                        || BASE58_ID.matcher(value).find());
        assertThat(metrics).doesNotContain(code).doesNotContain(inviteId).doesNotContain(wallet.address());
    }

    @Autowired
    private com.memetrade.testsupport.AdminSessions adminSessions;

    private static void check(String metric, String key, String value, List<String> problems) {
        if (!ALLOWED.contains(key)) {
            problems.add(metric + " has the label " + key);
        }
        if (BASE58_ID.matcher(value).matches() || UUID_TEXT.matcher(value).find() || value.contains("solana")) {
            problems.add(metric + "." + key + " carries an identifier");
        }
    }
}
