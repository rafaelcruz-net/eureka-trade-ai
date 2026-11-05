package com.memetrade.riskcontrol;

import static com.memetrade.riskcontrol.RiskGateFixtures.baseLimits;
import static com.memetrade.riskcontrol.RiskGateFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.riskcontrol.api.GateResult.Rejected;
import com.memetrade.riskcontrol.api.OrderGate;
import com.memetrade.riskcontrol.api.OrderSide;
import com.memetrade.riskcontrol.infra.RiskEvaluationPurgeJob;
import com.memetrade.testsupport.AbstractU6IT;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.ControllableMarketData;
import com.memetrade.testsupport.RiskRows;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Passo 12 of U6, WRITTEN BEFORE THE CONTROLLER (C21, BR4.1, BR4.2; NFR5.1, NFR6.2 to NFR6.4): the refused orders of
 * the caller's bot through the whole filter chain. The own person reads the page with its details and market inputs,
 * newest first, by cursor; the five cases without an evaluation of the person (another person's bot already evaluated,
 * a bot never evaluated, another tenant's bot, an unknown bot, a bot whose evaluations were all purged) are the same
 * 404 and never the data; invalid input is 400, another mode 403 and no method but GET exists.
 */
class RejectionRoutesIT extends AbstractU6IT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private OrderGate gate;

    @Autowired
    private RiskEvaluationPurgeJob purge;

    private TokenId token;

    /** A signed-in guest: the bearer token and the principal the cycle uses for that person. */
    private record Guest(String bearer, Principal principal) {}

    @BeforeEach
    void seed() {
        token = newToken();
        marketNow(token, "1.000000000000", "10000.000000");
    }

    @Test
    void theOwnPersonReadsThePageWithReasonsDetailsAndMarket() throws Exception {
        Guest guest = signIn();
        UUID bot = fundedBot(guest.principal(), "1000.000000");
        Rejected rejected = refuse(guest, bot, "100.000001");
        TokenId missing = newToken();
        marketNow(missing, "1.000000000000", "10000.000000");
        market.price(missing, ControllableMarketData.unknown());
        Rejected unknownPrice = (Rejected) inCycle(
                guest.principal(), () -> gate.evaluate(order(bot, missing, OrderSide.BUY, "100.000000"), baseLimits()));

        MockHttpServletResponse response = call(guest, get("/api/v1/bots/" + bot + "/rejections"));

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode page = body(response);
        assertThat(page.get("total").asLong()).isEqualTo(2);
        assertThat(page.get("totalCapped").asBoolean()).isFalse();
        assertThat(page.get("nextCursor").isNull()).isTrue();
        assertThat(page.get("items")).hasSize(2);
        JsonNode first = item(page, rejected.rejectionId());
        assertThat(first.get("reasons").get(0).asText()).isEqualTo("ORDER_SIZE");
        assertThat(first.get("limitsVersion").asLong()).isEqualTo(1);
        assertThat(first.get("evaluatedAt").asText()).isEqualTo(clock.instant().toString());
        assertThat(first.get("pauseTriggers")).isEmpty();
        assertThat(first.get("details").get(0).get("reason").asText()).isEqualTo("ORDER_SIZE");
        assertThat(first.get("details").get(0).get("measured").asText()).isEqualTo("100.000001");
        assertThat(first.get("details").get(0).get("limit").asText()).isEqualTo("100.000000");
        assertThat(first.get("details").get(0).get("unit").asText()).isEqualTo("USDC");
        JsonNode market = first.get("market");
        assertThat(market.get("referencePrice").get("status").asText()).isEqualTo("KNOWN");
        assertThat(market.get("referencePrice").get("value").asText()).isEqualTo("1.000000000000");
        assertThat(market.get("liquidity").get("value").asText()).isEqualTo("10000.000000");
        assertThat(market.get("estimatedSlippagePct").asText()).isEqualTo("1.5000");
        assertThat(market.get("orderValue").asText()).isEqualTo("100.000001");
        assertThat(first.get("mode").asText()).isEqualTo("PAPER");
        JsonNode unknown = item(page, unknownPrice.rejectionId()).get("market");
        assertThat(unknown.get("referencePrice").get("status").asText()).isEqualTo("UNKNOWN");
        assertThat(unknown.get("orderValue").asText()).isEqualTo("UNKNOWN");
        assertThat(response.getContentAsString())
                .doesNotContain(guest.principal().tenantId().toString());
    }

    @Test
    void thePagesGoNewestFirstByCursorWithinOneToAHundred() throws Exception {
        Guest guest = signIn();
        UUID bot = fundedBot(guest.principal(), "1000.000000");
        List<UUID> written = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            written.add(refuse(guest, bot, "100.00000" + (i + 1)).rejectionId());
            clock.advance(java.time.Duration.ofSeconds(1));
        }

        JsonNode first =
                body(call(guest, get("/api/v1/bots/" + bot + "/rejections").param("limit", "2")));
        JsonNode second = body(call(
                guest,
                get("/api/v1/bots/" + bot + "/rejections")
                        .param("limit", "2")
                        .param("cursor", first.get("nextCursor").asText())));
        JsonNode third = body(call(
                guest,
                get("/api/v1/bots/" + bot + "/rejections")
                        .param("limit", "2")
                        .param("cursor", second.get("nextCursor").asText())));

        List<String> seen = new ArrayList<>();
        for (JsonNode page : List.of(first, second, third)) {
            page.get("items").forEach(item -> seen.add(item.get("rejectionId").asText()));
        }
        assertThat(seen)
                .containsExactly(
                        written.get(4).toString(),
                        written.get(3).toString(),
                        written.get(2).toString(),
                        written.get(1).toString(),
                        written.get(0).toString());
        assertThat(third.get("nextCursor").isNull()).isTrue();
        assertThat(call(guest, get("/api/v1/bots/" + bot + "/rejections").param("limit", "100"))
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    void anEvaluatedBotWithoutRejectionsIsAnEmptyPage() throws Exception {
        Guest guest = signIn();
        UUID bot = fundedBot(guest.principal(), "1000.000000");
        inCycle(guest.principal(), () -> gate.evaluate(order(bot, token, OrderSide.BUY, "1.000000"), baseLimits()));

        JsonNode page = body(call(guest, get("/api/v1/bots/" + bot + "/rejections")));

        assertThat(page.get("items")).isEmpty();
        assertThat(page.get("total").asLong()).isZero();
    }

    /** The negative test of the route registry (C21 is CLIENT): another person's evaluated bot is 404, never data. */
    @Test
    void anotherPersonsEvaluatedBotIsNotFound() throws Exception {
        Guest owner = signIn();
        Guest stranger = signIn();
        UUID bot = fundedBot(owner.principal(), "1000.000000");
        refuse(owner, bot, "100.000001");

        MockHttpServletResponse response = call(stranger, get("/api/v1/bots/" + bot + "/rejections"));

        assertNotFound(response);
        assertThat(response.getContentAsString()).doesNotContain("ORDER_SIZE");
    }

    @Test
    void theFiveCasesWithoutAnEvaluationOfThePersonAreTheSame404() throws Exception {
        Guest guest = signIn();
        // 1. a bot of a colleague in the same tenant, already evaluated
        Principal colleague = samePersonTenant(guest.principal());
        UUID colleaguesBot = fundedBot(colleague, "1000.000000");
        inCycle(colleague, () -> gate.evaluate(order(colleaguesBot, token, OrderSide.BUY, "100.000001"), baseLimits()));
        // 2. the person's own bot never evaluated
        UUID neverEvaluated = fundedBot(guest.principal(), "1000.000000");
        // 3. a bot of another tenant, evaluated
        Guest otherTenant = signIn();
        UUID foreignBot = fundedBot(otherTenant.principal(), "1000.000000");
        refuse(otherTenant, foreignBot, "100.000001");
        // 4. an unknown bot
        UUID unknown = UUID.randomUUID();
        // 5. the person's bot whose only evaluation (approved, older than 31 days) was purged
        UUID purged = UUID.randomUUID();
        new RiskRows(appRole()).evaluation(guest.principal(), purged, "APPROVED", "40 days");
        purge.runOnce();

        List<String> bodies = new ArrayList<>();
        for (UUID bot : List.of(colleaguesBot, neverEvaluated, foreignBot, unknown, purged)) {
            MockHttpServletResponse response = call(guest, get("/api/v1/bots/" + bot + "/rejections"));
            assertNotFound(response);
            bodies.add(body(response).get("code").asText()
                    + body(response).get("title").asText());
        }
        assertThat(bodies).containsOnly(bodies.getFirst());
    }

    @Test
    void invalidInputIs400WithoutSqlOrStackTrace() throws Exception {
        Guest guest = signIn();
        UUID bot = fundedBot(guest.principal(), "1000.000000");
        refuse(guest, bot, "100.000001");
        String base = "/api/v1/bots/" + bot + "/rejections";

        for (MockHttpServletRequestBuilder request : List.of(
                get(base).param("cursor", "not-a-cursor"),
                get(base)
                        .param(
                                "cursor",
                                new com.memetrade.shared.web.KeysetCursor(clock.instant(), UUID.randomUUID())
                                        .encode("trades:" + bot)),
                get(base).param("limit", "0"),
                get(base).param("limit", "101"),
                get(base).param("limit", "ten"),
                get(base).param("tenantId", UUID.randomUUID().toString()),
                get(base).param("limit", "5").param("limit", "6"),
                get("/api/v1/bots/not-a-uuid/rejections"))) {
            MockHttpServletResponse response = call(guest, request);
            assertThat(response.getStatus()).isEqualTo(400);
            JsonNode problem = body(response);
            assertThat(problem.get("code").asText()).isEqualTo("VALIDATION_FAILED");
            assertThat(response.getContentAsString())
                    .doesNotContainIgnoringCase("select")
                    .doesNotContain("Exception")
                    .doesNotContain("at com.");
        }
    }

    @Test
    void aModeOtherThanPaperIsRefusedWith403AndPaperIsAccepted() throws Exception {
        Guest guest = signIn();
        UUID bot = fundedBot(guest.principal(), "1000.000000");
        refuse(guest, bot, "100.000001");

        // The mode guard reads the query string (as a browser sends it), like LedgerRoutesIT.
        MockHttpServletResponse real = call(guest, get("/api/v1/bots/" + bot + "/rejections?mode=REAL"));
        assertThat(real.getStatus()).isEqualTo(403);
        assertThat(body(real).get("code").asText()).isEqualTo("REAL_MODE_UNAVAILABLE");
        assertThat(call(guest, get("/api/v1/bots/" + bot + "/rejections?mode=PAPER"))
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    void noMethodButGetExistsAndWithoutATokenItIs401() throws Exception {
        Guest guest = signIn();
        UUID bot = fundedBot(guest.principal(), "1000.000000");
        refuse(guest, bot, "100.000001");
        String route = "/api/v1/bots/" + bot + "/rejections";

        for (MockHttpServletRequestBuilder request : List.of(
                post(route).header("Idempotency-Key", UUID.randomUUID().toString()),
                put(route).header("Idempotency-Key", UUID.randomUUID().toString()),
                patch(route).header("Idempotency-Key", UUID.randomUUID().toString()),
                delete(route).header("Idempotency-Key", UUID.randomUUID().toString()))) {
            assertThat(call(guest, request).getStatus()).isIn(404, 405);
        }
        assertThat(body(call(guest, get(route))).get("total").asLong()).isEqualTo(1);
        assertThat(mvc.perform(get(route)).andReturn().getResponse().getStatus())
                .isEqualTo(401);
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private Guest signIn() throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.Seeded seeded = TestAccounts.betaUser(jdbc, wallet.address());
        AuthFlow auth = new AuthFlow(mvc, json);
        return new Guest(
                "Bearer " + auth.accessToken(auth.login(wallet)),
                new Principal(seeded.tenantId(), seeded.userId(), Role.BETA_USER));
    }

    private Rejected refuse(Guest guest, UUID bot, String quantity) {
        return (Rejected) inCycle(
                guest.principal(), () -> gate.evaluate(order(bot, token, OrderSide.BUY, quantity), baseLimits()));
    }

    private MockHttpServletResponse call(Guest guest, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", guest.bearer()))
                .andReturn()
                .getResponse();
    }

    private JsonNode body(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString());
    }

    private static JsonNode item(JsonNode page, UUID rejectionId) {
        for (JsonNode item : page.get("items")) {
            if (item.get("rejectionId").asText().equals(rejectionId.toString())) {
                return item;
            }
        }
        throw new AssertionError("rejection " + rejectionId + " not in the page");
    }

    private void assertNotFound(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(body(response).get("code").asText()).isEqualTo("NOT_FOUND");
    }
}
