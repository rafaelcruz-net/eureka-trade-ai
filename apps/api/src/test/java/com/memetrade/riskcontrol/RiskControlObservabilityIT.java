package com.memetrade.riskcontrol;

import static com.memetrade.riskcontrol.RiskGateFixtures.baseLimits;
import static com.memetrade.riskcontrol.RiskGateFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.audit.api.AuditActions;
import com.memetrade.identity.api.Principal;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.riskcontrol.api.GateResult.Rejected;
import com.memetrade.riskcontrol.api.OrderGate;
import com.memetrade.riskcontrol.api.OrderSide;
import com.memetrade.testsupport.AbstractU6IT;
import com.memetrade.testsupport.OutboxEvents;
import com.memetrade.testsupport.SensitiveValueScanner;
import com.memetrade.testsupport.SensitiveValueScanner.Kind;
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
import org.springframework.http.ResponseEntity;

/**
 * Passo 14 of U6 (NFR9.1 to NFR9.5, NFR7.1): the health component {@code riskcontrol} outside readiness and liveness;
 * the two counters with the label {@code cause} only; and a test cycle with a rejection whose logs, outbox event and
 * Prometheus output carry identifiers and closed values only, never the wallet address, a token or the
 * {@code Authorization} header (the sweep of LogScanIT and MetricsLabelsIT of U1 over the U6 cycle).
 */
@ExtendWith(OutputCaptureExtension.class)
class RiskControlObservabilityIT extends AbstractU6IT {

    private static final Pattern RISK_METRIC = Pattern.compile("(?m)^(riskcontrol_\\w+)\\{([^}]*)\\}");
    private static final Pattern LABEL = Pattern.compile("(\\w+)=\"([^\"]*)\"");

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private OrderGate gate;

    @Test
    void theRiskcontrolComponentIsInTheHealthButNotInReadinessNorLiveness() throws Exception {
        JsonNode health = json.readTree(get("/actuator/health").getBody());
        JsonNode readiness = json.readTree(get("/actuator/health/readiness").getBody());
        JsonNode liveness = json.readTree(get("/actuator/health/liveness").getBody());

        assertThat(health.at("/components/riskcontrol/status").asText()).isIn("UP", "DOWN");
        assertThat(readiness.at("/components/riskcontrol").isMissingNode()).isTrue();
        assertThat(liveness.at("/components/riskcontrol").isMissingNode()).isTrue();
        assertThat(get("/actuator/health/readiness").getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void theCountersExistWithTheCauseLabelOnly() {
        String prometheus = get("/actuator/prometheus").getBody();

        assertThat(prometheus)
                .contains("riskcontrol_evaluation_errors_total")
                .contains("riskcontrol_purge_errors_total");
        List<String> problems = new ArrayList<>();
        Matcher metric = RISK_METRIC.matcher(prometheus);
        while (metric.find()) {
            Matcher label = LABEL.matcher(metric.group(2));
            while (label.find()) {
                if (!List.of("cause", "application").contains(label.group(1))) {
                    problems.add(metric.group(1) + " has the label " + label.group(1));
                }
                if ("cause".equals(label.group(1))
                        && !List.of("MARKET_DATA", "PORTFOLIO", "DATABASE", "OTHER")
                                .contains(label.group(2))) {
                    problems.add(metric.group(1) + " has the cause " + label.group(2));
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void aCycleWithARejectionLogsAndAuditsIdentifiersOnly(CapturedOutput output) throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.Seeded seeded = TestAccounts.betaUser(jdbc, wallet.address());
        Principal person = new Principal(seeded.tenantId(), seeded.userId(), com.memetrade.identity.api.Role.BETA_USER);
        TokenId token = newToken();
        marketNow(token, "1.000000000000", "10000.000000");
        UUID bot = fundedBot(person, "1000.000000");

        Rejected rejected = (Rejected)
                inCycle(person, () -> gate.evaluate(order(bot, token, OrderSide.BUY, "100.000001"), baseLimits()));

        String logs = output.getAll();
        assertThat(logs)
                .contains("Order rejected")
                .contains(rejected.rejectionId().toString())
                .contains(bot.toString());
        String rejectionLine = logs.lines()
                .filter(line -> line.contains(rejected.rejectionId().toString()))
                .findFirst()
                .orElseThrow();
        assertThat(json.readTree(rejectionLine).path("level").asText()).isEqualTo("INFO");
        assertThat(rejectionLine).contains("mainReason=ORDER_SIZE");

        SensitiveValueScanner scanner = new SensitiveValueScanner().register(Kind.WALLET_ADDRESS, wallet.address());
        List<SensitiveValueScanner.Finding> findings = new ArrayList<>(scanner.scan("api logs", logs));
        for (String payload : OutboxEvents.payloads(jdbc, AuditActions.ORDER_REJECTED)) {
            findings.addAll(scanner.scan("ORDER_REJECTED payload", payload));
        }
        findings.addAll(scanner.scan("prometheus", get("/actuator/prometheus").getBody()));
        assertThat(findings).as(SensitiveValueScanner.report(findings)).isEmpty();
        String market = jdbc.sql("select payload::text from audit_outbox where payload->'details'->>'rejectionId' = :r")
                .param("r", rejected.rejectionId().toString())
                .query(String.class)
                .single();
        assertThat(market).doesNotContain("1.000000000000").doesNotContain("10000.000000");
    }

    private ResponseEntity<String> get(String path) {
        return rest.getForEntity("http://127.0.0.1:" + managementPort + path, String.class);
    }
}
