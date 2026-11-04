package com.memetrade.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketSourceAdapter.TokenInfo;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.SourceStatus.State;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.domain.MarketStore;
import com.memetrade.shared.web.CorrelationId;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.AdminSessions;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/** The two U4 routes through the whole filter chain (Step 13, C2, NFR6.1, NFR6.2, BR3.9, BR1.4). */
@Tag("u4")
class ScannerRoutesIT extends AbstractPostgresIT {

    private static final List<String> ROUTES = List.of(
            "/api/v1/scanner/tokens", "/api/v1/tokens/solana/FxCommonA1111111111111111111111111111111111/assessment");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private AdminSessions adminSessions;

    @Autowired
    private MarketStore store;

    private String guest;
    private SourceRef source;

    @BeforeEach
    void signIn() throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.betaUser(jdbc, wallet.address());
        AuthFlow auth = new AuthFlow(mvc, json);
        guest = auth.accessToken(auth.login(wallet));
        source = new SourceRef("rt-" + UUID.randomUUID().toString().substring(0, 8), "Rotas", true);
        store.registerSource(source, Duration.ofSeconds(30));
        store.updateSourceStatus(
                source.sourceId(), State.OK, Optional.empty(), clock.instant(), Optional.of(clock.instant()));
    }

    @Test
    void withoutATokenEachRouteIs401() throws Exception {
        for (String route : ROUTES) {
            assertThat(mvc.perform(get(route)).andReturn().getResponse().getStatus())
                    .as(route)
                    .isEqualTo(401);
        }
    }

    @Test
    void anAdministratorTokenIs403ForbiddenRoleOnEachGuestRouteAndAudited() throws Exception {
        AdminSessions.AdminSession admin = adminSessions.openAdminSession();
        for (String route : ROUTES) {
            String correlation = UUID.randomUUID().toString();
            MockHttpServletResponse response = mvc.perform(get(route)
                            .header("Authorization", admin.bearer())
                            .header(CorrelationId.HEADER, correlation))
                    .andReturn()
                    .getResponse();

            assertThat(response.getStatus()).as(route).isEqualTo(403);
            assertThat(response.getContentAsString()).contains("FORBIDDEN_ROLE").doesNotContain("items");
            assertThat(jdbc.sql("select payload->>'action' from audit_outbox where payload->>'correlationId' = :c")
                            .param("c", correlation)
                            .query(String.class)
                            .list())
                    .containsExactly("REQUEST_FORBIDDEN");
        }
    }

    @Test
    void aGuestGetsTheFilteredListWithRisk() throws Exception {
        String mint = seed(true);

        JsonNode page = body(get("/api/v1/scanner/tokens?sourceId=" + source.sourceId()), 200);

        assertThat(page.get("total").asLong()).isEqualTo(1);
        JsonNode row = page.get("items").get(0);
        assertThat(row.get("mint").asText()).isEqualTo(mint);
        assertThat(row.get("risk").get("status").asText()).isIn("AVAILABLE", "UNAVAILABLE");
        assertThat(page.get("excludedByUnknown").asLong()).isZero();
    }

    @Test
    void invalidParametersAre400WithTheListOfFields() throws Exception {
        JsonNode problem = body(get("/api/v1/scanner/tokens?minLiquidity=-1&sort=PRICE&maxRiskScore=200"), 400);

        assertThat(problem.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(problem.get("fields").findValuesAsText("field"))
                .containsExactlyInAnyOrder("minLiquidity", "sort", "maxRiskScore");
        assertThat(body(get("/api/v1/tokens/Solana!/x/assessment"), 400)
                        .get("fields")
                        .findValuesAsText("field"))
                .containsExactlyInAnyOrder("chain", "mint");
    }

    @Test
    void theDetailShowsHowTheAssessmentWasMade() throws Exception {
        String mint = seed(true);

        JsonNode detail = body(get("/api/v1/tokens/solana/" + mint + "/assessment"), 200);

        assertThat(detail.get("inValidation").asBoolean()).isTrue();
        assertThat(detail.get("weightSetVersion").isTextual()).isTrue();
        assertThat(detail.get("coverage").asText()).isEqualTo("100.0000");
        assertThat(detail.get("minCoverage").asText()).isEqualTo("80.0000");
        assertThat(detail.get("decisionHint").asText()).isEqualTo("ELIGIBLE");
        assertThat(detail.get("computedAt").asText()).endsWith("Z");
        assertThat(detail.get("factors")).hasSize(4);
        JsonNode liquidity = detail.get("factors").get(0);
        assertThat(liquidity.get("factorId").asText()).isEqualTo("LIQUIDITY");
        assertThat(liquidity.get("weight").asText()).isEqualTo("35.0000");
        assertThat(liquidity.get("input").get("source").get("sourceId").asText())
                .isEqualTo(source.sourceId());
        assertThat(liquidity.get("input").get("stale").asBoolean()).isFalse();
        assertThat(liquidity.get("contribution").isTextual()).isTrue();
    }

    @Test
    void anUnavailableIndicatorIsNoTradeWithItsReasonAndUnknownContributions() throws Exception {
        String mint = seed(false);

        JsonNode detail = body(get("/api/v1/tokens/solana/" + mint + "/assessment"), 200);

        assertThat(detail.get("risk").get("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(detail.get("risk").get("reason").asText()).isEqualTo("INSUFFICIENT_COVERAGE");
        assertThat(detail.get("decisionHint").asText()).isEqualTo("NO_TRADE");
        assertThat(detail.get("risk").has("score")).isFalse();
        assertThat(detail.get("factors")
                        .get(1)
                        .get("contribution")
                        .get("status")
                        .asText())
                .isEqualTo("UNKNOWN");
    }

    @Test
    void aTokenWithoutARecentObservationIs404() throws Exception {
        assertThat(body(get("/api/v1/tokens/solana/" + SyntheticIds.mint() + "/assessment"), 404)
                        .get("code")
                        .asText())
                .isEqualTo("NOT_FOUND");
    }

    @Test
    void withEverySourceFailedTheListIs503WithTheStateOfEachSource() throws Exception {
        List<Map<String, Object>> before =
                jdbc.sql("select source_id, status from data_source").query().listOfRows();
        try {
            jdbc.sql("update data_source set status = 'FAILED'").update();

            JsonNode problem = body(get("/api/v1/scanner/tokens"), 503);

            assertThat(problem.get("code").asText()).isEqualTo("SOURCE_UNAVAILABLE");
            assertThat(problem.get("sources").findValuesAsText("status")).containsOnly("FAILED");
            assertThat(problem.has("items")).isFalse();
        } finally {
            for (Map<String, Object> row : before) {
                jdbc.sql("update data_source set status = :s where source_id = :id")
                        .param("s", row.get("status"))
                        .param("id", row.get("source_id"))
                        .update();
            }
        }
    }

    /** A token of this test's source; with {@code complete} every factor is known, otherwise holders and volume are not. */
    private String seed(boolean complete) {
        TokenId token = new TokenId("solana", SyntheticIds.mint());
        Instant now = clock.instant();
        store.upsertToken(new TokenInfo(token, "RTE", null, 6), now);
        observe(token, MarketField.PRICE, "0.5", now);
        observe(token, MarketField.LIQUIDITY, "20000", now);
        if (complete) {
            observe(token, MarketField.HOLDERS, "700", now);
            observe(token, MarketField.VOLUME_24H, "50000", now);
            Instant hourAgo = now.minus(Duration.ofMinutes(62));
            store.insertSample(
                    token,
                    source.sourceId(),
                    MarketField.PRICE,
                    new MarketValue.Known(new BigDecimal("0.45"), source, hourAgo, false),
                    hourAgo);
        }
        return token.mint();
    }

    private void observe(TokenId token, MarketField field, String value, Instant at) {
        store.upsertObservation(
                token, source.sourceId(), field, new MarketValue.Known(new BigDecimal(value), source, at, false), at);
    }

    private JsonNode body(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, int expected)
            throws Exception {
        MockHttpServletResponse response = mvc.perform(request.header("Authorization", "Bearer " + guest))
                .andReturn()
                .getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected);
        return json.readTree(response.getContentAsString());
    }
}
