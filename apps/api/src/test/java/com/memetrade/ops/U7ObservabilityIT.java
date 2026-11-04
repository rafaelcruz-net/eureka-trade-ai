package com.memetrade.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.audit.api.AuditActions;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.ledger.api.PaperExecution;
import com.memetrade.ledger.api.PaperExecution.ExecutionResult;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.pnl.infra.EquityMarkingJob;
import com.memetrade.pnl.infra.ReconciliationJob;
import com.memetrade.testsupport.AbstractU7IT;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.LedgerFacts;
import com.memetrade.testsupport.PaperDriver;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Passo 19 (NFR7.1, NFR9.1, NFR9.3, NFR9.4, NFR9.5): a whole PAPER flow - fund, buy, sell, closing, P&L, ledger,
 * reconciliation, the three jobs - then: the logs carry no token, address, amount, price nor mint; the metrics label
 * the U7 routes by template only; the trail has one event per action and none for a Duplicate; the health component
 * {@code ledgerJobs} exists outside the readiness group.
 */
@ExtendWith(OutputCaptureExtension.class)
class U7ObservabilityIT extends AbstractU7IT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private PaperExecution execution;

    @Autowired
    private EquityMarkingJob marking;

    @Autowired
    private ReconciliationJob reconciliation;

    @Autowired
    private com.memetrade.ledger.infra.PendingCloseJob pendingClose;

    @Autowired
    private TestRestTemplate rest;

    @LocalManagementPort
    private int managementPort;

    @Test
    void aWholeFlowLeavesNoSensitiveValueInLogsOrMetricsAndOneEventPerAction(CapturedOutput output) throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.Seeded seeded = TestAccounts.betaUser(jdbc, wallet.address());
        Principal person = new Principal(seeded.tenantId(), seeded.userId(), Role.BETA_USER);
        AuthFlow auth = new AuthFlow(mvc, json);
        String token = auth.accessToken(auth.login(wallet));
        PaperDriver driver = new PaperDriver(execution, transactions);
        TokenId mint = newToken();
        market.set(mint, "1.000000", "100000.000000", 6);

        UUID bot = driver.fundedBot(person, "1000.000000");
        String key = UUID.randomUUID().toString();
        var request = PaperDriver.request(
                bot, com.memetrade.ledger.api.PaperEnums.OrderSide.BUY, mint, "100", UUID.randomUUID(), 1);
        ExecutionResult.Filled buy = (ExecutionResult.Filled) driver.order(person, request, key);
        driver.order(person, request, key);
        market.set(mint, "1.500000", "100000.000000", 6);
        driver.sell(person, bot, mint, "40");
        market.set(mint, "1.200000", "100000.000000", 6);
        driver.close(person, bot, UUID.randomUUID().toString());
        marking.runOnce();
        pendingClose.runOnce();
        reconciliation.runOnce();
        for (String route : List.of(
                "/api/v1/pnl?from=2026-10-28T00:00:00Z&to=2026-10-29T00:00:00Z",
                "/api/v1/ledger?botId=" + bot,
                "/api/v1/bots/" + bot + "/trades",
                "/api/v1/bots/" + bot + "/day-usage",
                "/api/v1/reconciliation")) {
            assertThat(mvc.perform(get(route).header("Authorization", "Bearer " + token))
                            .andReturn()
                            .getResponse()
                            .getStatus())
                    .as(route)
                    .isEqualTo(200);
        }
        mvc.perform(post("/api/v1/reconciliation/run")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString()));

        String logs = output.getAll();
        assertThat(logs)
                .doesNotContain(token)
                .doesNotContain("Bearer ")
                .doesNotContain(wallet.address())
                .doesNotContain(mint.mint())
                .doesNotContain("100.600000")
                .doesNotContain("1.006000000000")
                .doesNotContain("48.425");

        String metrics = rest.getForEntity("http://127.0.0.1:" + managementPort + "/actuator/prometheus", String.class)
                .getBody();
        assertThat(metrics)
                .contains("uri=\"/api/v1/bots/{botId}/trades\"")
                .contains("uri=\"/api/v1/pnl\"")
                .doesNotContain(bot.toString())
                .doesNotContain(person.userId().toString())
                .doesNotContain(mint.mint());
        List<String> labels = new ArrayList<>();
        Matcher matcher = Pattern.compile("uri=\"([^\"]*)\"").matcher(metrics);
        while (matcher.find()) {
            labels.add(matcher.group(1));
        }
        assertThat(labels).noneMatch(uri -> uri.matches(".*[0-9a-f]{8}-[0-9a-f]{4}-.*"));

        LedgerFacts facts = new LedgerFacts(jdbc);
        assertThat(facts.events(AuditActions.PAPER_ORDER_FILLED, "order:" + buy.orderId()))
                .as("the Duplicate published nothing")
                .isEqualTo(1);
        assertThat(facts.events(AuditActions.PAPER_ACCOUNT_FUNDED, "bot:" + bot))
                .isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from audit_outbox where payload->>'action' = 'PAPER_ORDER_FILLED'"
                                + " and payload->>'tenantId' = :t")
                        .param("t", person.tenantId().toString())
                        .query(Long.class)
                        .single())
                .as("buy, sell and the CLOSE")
                .isEqualTo(3);
        // Only the U7 actions: LOGIN_SUCCEEDED of U1 keeps the full address in details by design (U2 BR2.4).
        assertThat(jdbc.sql("select payload::text from audit_outbox where payload->>'tenantId' = :t"
                                + " and (payload->>'action' like 'PAPER_%' or payload->>'action' like 'POSITION_%'"
                                + " or payload->>'action' like 'LEDGER_%')")
                        .param("t", person.tenantId().toString())
                        .query(String.class)
                        .list())
                .allSatisfy(payload -> assertThat(payload)
                        .doesNotContain("100.600000")
                        .doesNotContain(mint.mint())
                        .doesNotContain(wallet.address()));
    }

    @Test
    void theHealthComponentLedgerJobsExistsOutsideReadiness() throws Exception {
        marking.runOnce();
        pendingClose.runOnce();
        reconciliation.runOnce();

        JsonNode health =
                json.readTree(rest.getForEntity("http://127.0.0.1:" + managementPort + "/actuator/health", String.class)
                        .getBody());
        JsonNode readiness = json.readTree(
                rest.getForEntity("http://127.0.0.1:" + managementPort + "/actuator/health/readiness", String.class)
                        .getBody());

        assertThat(health.get("components").has("ledgerJobs")).isTrue();
        assertThat(health.get("components").get("ledgerJobs").get("status").asText())
                .isIn("UP", "DEGRADED");
        assertThat(health.get("components").get("ledgerJobs").get("details").has("peopleWithDivergences"))
                .isTrue();
        assertThat(readiness.has("components") && readiness.get("components").has("ledgerJobs"))
                .isFalse();
    }
}
