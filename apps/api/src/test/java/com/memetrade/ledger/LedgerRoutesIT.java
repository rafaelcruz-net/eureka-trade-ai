package com.memetrade.ledger;

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
import com.memetrade.ledger.api.PaperExecution;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.pnl.web.ReconciliationRunLimiter;
import com.memetrade.pnl.web.ReconciliationRunLimiterEvictionJob;
import com.memetrade.testsupport.AbstractU7IT;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.PaperDriver;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Passo 17 (written before the controllers): the routes of C5 through the whole filter chain (BR4.3, BR7.5, BR8.3,
 * BR9.1, BR10.2, NFR5.1, NFR6.3, NFR6.5, NFR6.6): each route for the own person, a bot of another person 404 and
 * never its data, a bot not activated empty, two tenants, {@code mode} other than PAPER 403, validation 400, "check
 * now" with Idempotency-Key and 6 per minute (429 with Retry-After) and no write verb.
 */
class LedgerRoutesIT extends AbstractU7IT {

    private static final String FROM = "2026-10-28T00:00:00Z";
    private static final String TO = "2026-10-29T00:00:00Z";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private PaperExecution execution;

    @Autowired
    private ReconciliationRunLimiter limiter;

    @Autowired
    private ReconciliationRunLimiterEvictionJob eviction;

    private PaperDriver driver;
    private TokenId token;

    /** A signed-in guest: the bearer token and the principal the cycle uses for that person. */
    private record Guest(String bearer, Principal principal) {}

    @BeforeEach
    void seed() {
        driver = new PaperDriver(execution, transactions);
        token = newToken();
        market.set(token, "1.000000", "100000.000000", 6);
    }

    private Guest signIn() throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.Seeded seeded = TestAccounts.betaUser(jdbc, wallet.address());
        AuthFlow auth = new AuthFlow(mvc, json);
        return new Guest(
                "Bearer " + auth.accessToken(auth.login(wallet)),
                new Principal(seeded.tenantId(), seeded.userId(), Role.BETA_USER));
    }

    private UUID botWithTrades(Guest guest) {
        UUID bot = driver.fundedBot(guest.principal(), "1000.000000");
        driver.buy(guest.principal(), bot, token, "100");
        return bot;
    }

    private MockHttpServletResponse call(Guest guest, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", guest.bearer()))
                .andReturn()
                .getResponse();
    }

    private JsonNode body(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString());
    }

    private MockHttpServletRequestBuilder run(String key) {
        return post("/api/v1/reconciliation/run").header("Idempotency-Key", key);
    }

    private static List<MockHttpServletRequestBuilder> routesOf(UUID bot) {
        return List.of(
                get("/api/v1/bots/" + bot + "/trades"),
                get("/api/v1/bots/" + bot + "/day-usage"),
                get("/api/v1/ledger").param("botId", bot.toString()),
                get("/api/v1/pnl")
                        .param("botId", bot.toString())
                        .param("from", FROM)
                        .param("to", TO),
                get("/api/v1/reconciliation").param("botId", bot.toString()));
    }

    @Test
    void eachRouteAnswersTheOwnPersonWithDecimalsAsTextAndPaperMode() throws Exception {
        Guest guest = signIn();
        UUID bot = botWithTrades(guest);

        JsonNode trades = body(call(guest, get("/api/v1/bots/" + bot + "/trades")));
        JsonNode ledger = body(call(guest, get("/api/v1/ledger").param("botId", bot.toString())));
        JsonNode usage = body(call(guest, get("/api/v1/bots/" + bot + "/day-usage")));
        JsonNode pnl = body(call(guest, get("/api/v1/pnl").param("from", FROM).param("to", TO)));

        assertThat(trades.get("total").asLong()).isEqualTo(1);
        JsonNode trade = trades.get("items").get(0);
        assertThat(trade.get("mode").asText()).isEqualTo("PAPER");
        assertThat(trade.get("fillPrice").isTextual()).isTrue();
        assertThat(trade.get("fillPrice").asText()).isEqualTo("1.006000000000");
        assertThat(trade.get("idempotencyKey").asText()).isNotBlank();
        assertThat(trade.get("limitsVersion").asLong()).isEqualTo(1);
        assertThat(trade.get("simulationPolicyVersion").asText()).isEqualTo("v1");
        assertThat(ledger.get("items")).hasSize(4);
        assertThat(ledger.get("items").get(0).get("amount").isTextual()).isTrue();
        assertThat(usage.get("exposure").asText()).isEqualTo("100.600000");
        // BR7.2: the day result is realized minus fees, so the fee of the buy is the loss of the day.
        assertThat(usage.get("dayLoss").asText()).isEqualTo("0.301800");
        assertThat(usage.get("utcDay").asText()).isEqualTo("2026-10-28");
        assertThat(usage.has("maxDailyLoss")).isFalse();
        assertThat(pnl.get("fees").asText()).isEqualTo("0.301800");
        assertThat(pnl.get("unrealized").get("status").asText()).isEqualTo("KNOWN");
        assertThat(pnl.get("empty").asBoolean()).isFalse();
        assertThat(pnl.get("byToken").get(0).get("mark").get("status").asText()).isEqualTo("KNOWN");
    }

    @Test
    void anotherPersonsBotIsNotFound() throws Exception {
        Guest owner = signIn();
        Guest stranger = signIn();
        UUID bot = botWithTrades(owner);

        for (MockHttpServletRequestBuilder route : routesOf(bot)) {
            MockHttpServletResponse response = call(stranger, route);
            assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(404);
            assertThat(body(response).get("code").asText()).isEqualTo("NOT_FOUND");
            assertThat(response.getContentAsString())
                    .doesNotContain("100.600000")
                    .doesNotContain(token.mint());
        }
    }

    @Test
    void aBotNotYetActivatedIsEmpty() throws Exception {
        Guest guest = signIn();
        UUID bot = UUID.randomUUID();

        assertThat(body(call(guest, get("/api/v1/bots/" + bot + "/trades"))).get("items"))
                .isEmpty();
        assertThat(body(call(guest, get("/api/v1/bots/" + bot + "/day-usage")))
                        .get("exposure")
                        .asText())
                .isEqualTo("0.000000");
        assertThat(body(call(guest, get("/api/v1/ledger").param("botId", bot.toString())))
                        .get("total")
                        .asLong())
                .isZero();
        JsonNode pnl = body(call(
                guest,
                get("/api/v1/pnl")
                        .param("botId", bot.toString())
                        .param("from", FROM)
                        .param("to", TO)));
        assertThat(pnl.get("empty").asBoolean()).isTrue();
        assertThat(pnl.get("realized").asText()).isEqualTo("0.000000");
        assertThat(body(call(guest, get("/api/v1/reconciliation")))
                        .get("status")
                        .asText())
                .as("no PAPER account, nothing to reconcile")
                .isEqualTo("RECONCILED");
    }

    @Test
    void twoTenantsNeverSeeEachOthersLedger() throws Exception {
        Guest first = signIn();
        Guest second = signIn();
        UUID firstBot = botWithTrades(first);
        botWithTrades(second);

        JsonNode firstLedger = body(call(first, get("/api/v1/ledger")));
        JsonNode secondRun = body(call(second, run(UUID.randomUUID().toString())));

        assertThat(firstLedger.get("total").asLong()).isEqualTo(4);
        firstLedger.get("items").forEach(item -> assertThat(item.get("botId").asText())
                .isEqualTo(firstBot.toString()));
        assertThat(secondRun.get("status").asText()).isEqualTo("RECONCILED");
        assertThat(secondRun.toString()).doesNotContain(firstBot.toString());
    }

    @Test
    void aModeOtherThanPaperIsRefusedWith403() throws Exception {
        Guest guest = signIn();

        MockHttpServletResponse response = call(guest, get("/api/v1/ledger?mode=REAL"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(body(response).get("code").asText()).isEqualTo("REAL_MODE_UNAVAILABLE");
    }

    @Test
    void invalidParametersAreValidationFailed() throws Exception {
        Guest guest = signIn();
        List<MockHttpServletRequestBuilder> invalid = List.of(
                get("/api/v1/bots/not-a-uuid/trades"),
                get("/api/v1/ledger").param("limit", "0"),
                get("/api/v1/ledger").param("limit", "101"),
                get("/api/v1/ledger").param("cursor", "not-a-cursor"),
                get("/api/v1/ledger").param("unknown", "x"),
                get("/api/v1/pnl").param("from", TO).param("to", FROM),
                get("/api/v1/pnl").param("from", "2025-10-27T00:00:00Z").param("to", TO),
                get("/api/v1/pnl").param("from", "yesterday").param("to", TO),
                get("/api/v1/pnl").param("to", TO),
                get("/api/v1/reconciliation").param("botId", "x"));

        for (MockHttpServletRequestBuilder request : invalid) {
            MockHttpServletResponse response = call(guest, request);
            assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(400);
            assertThat(body(response).get("code").asText()).isEqualTo("VALIDATION_FAILED");
        }
    }

    @Test
    void theLedgerPagesByCursor() throws Exception {
        Guest guest = signIn();
        UUID bot = botWithTrades(guest);

        JsonNode first = body(
                call(guest, get("/api/v1/ledger").param("botId", bot.toString()).param("limit", "3")));
        JsonNode second = body(call(
                guest,
                get("/api/v1/ledger")
                        .param("botId", bot.toString())
                        .param("limit", "3")
                        .param("cursor", first.get("nextCursor").asText())));

        assertThat(first.get("items")).hasSize(3);
        assertThat(second.get("items")).hasSize(1);
        assertThat(second.get("nextCursor").isNull())
                .as("null on the last page")
                .isTrue();
        assertThat(body(call(
                                guest,
                                get("/api/v1/ledger")
                                        .param("botId", UUID.randomUUID().toString())
                                        .param("cursor", first.get("nextCursor").asText())))
                        .get("code")
                        .asText())
                .as("a cursor of another query")
                .isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void checkNowNeedsTheKeyReplaysTheSameKeyAndAllowsSixPerMinute() throws Exception {
        Guest guest = signIn();
        botWithTrades(guest);
        String key = UUID.randomUUID().toString();

        assertThat(call(guest, post("/api/v1/reconciliation/run")).getStatus()).isEqualTo(400);
        JsonNode first = body(call(guest, run(key)));
        JsonNode replay = body(call(guest, run(key)));
        assertThat(replay.get("checkedAt")).as(replay.toString()).isEqualTo(first.get("checkedAt"));
        assertThat(first.get("trigger").asText()).isEqualTo("ON_DEMAND");

        for (int i = 0; i < 5; i++) {
            assertThat(call(guest, run(UUID.randomUUID().toString())).getStatus())
                    .isEqualTo(200);
        }
        MockHttpServletResponse limited = call(guest, run(UUID.randomUUID().toString()));
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isNotBlank();
        assertThat(body(limited).get("code").asText()).isEqualTo("RATE_LIMITED");

        assertThat(call(guest, get("/api/v1/reconciliation")).getStatus())
                .as("the reads have no limit")
                .isEqualTo(200);
        clock.advance(Duration.ofSeconds(61));
        assertThat(call(guest, run(UUID.randomUUID().toString())).getStatus()).isEqualTo(200);
    }

    @Test
    void theLimiterKeysIdleForTwoMinutesAreEvicted() throws Exception {
        Guest guest = signIn();
        call(guest, run(UUID.randomUUID().toString()));
        assertThat(limiter.trackedKeys()).isPositive();

        clock.advance(Duration.ofMinutes(3));
        eviction.evict();

        assertThat(limiter.trackedKeys()).isZero();
        assertThat(limiter.limit()).isEqualTo(6);
    }

    @Test
    void noRouteChangesOrDeletesTheLedger() throws Exception {
        Guest guest = signIn();
        UUID bot = botWithTrades(guest);

        for (MockHttpServletRequestBuilder request : List.of(
                put("/api/v1/ledger")
                        .header("Idempotency-Key", UUID.randomUUID().toString()),
                delete("/api/v1/ledger")
                        .header("Idempotency-Key", UUID.randomUUID().toString()),
                patch("/api/v1/bots/" + bot + "/trades")
                        .header("Idempotency-Key", UUID.randomUUID().toString()),
                post("/api/v1/pnl").header("Idempotency-Key", UUID.randomUUID().toString()))) {
            assertThat(call(guest, request).getStatus()).isIn(404, 405);
        }
        assertThat(body(call(guest, get("/api/v1/ledger").param("botId", bot.toString())))
                        .get("total")
                        .asLong())
                .isEqualTo(4);
    }

    @Test
    void withoutATokenEveryRouteIs401() throws Exception {
        for (MockHttpServletRequestBuilder route : routesOf(UUID.randomUUID())) {
            assertThat(mvc.perform(route).andReturn().getResponse().getStatus()).isEqualTo(401);
        }
    }
}
