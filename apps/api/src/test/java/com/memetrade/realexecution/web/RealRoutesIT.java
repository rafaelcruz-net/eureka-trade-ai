package com.memetrade.realexecution.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.botwallet.api.RealChain;
import com.memetrade.realexecution.AbstractRealExecutionIT;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.TestAccounts;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Step 13 of the U4, written after the code (R3, BR10.2, BR1.1; U4-NFR6.1): the routes of the REAL orders answer only
 * the person of the token; an order of another person is 404 on every route; the amounts travel as integer text.
 */
@Tag("u4-real-execution")
class RealRoutesIT extends AbstractRealExecutionIT {

    private String bearer(Setup setup) throws Exception {
        TestAccounts.acceptTerms(jdbc, setup.tenantId(), setup.userId(), "v3");
        AuthFlow auth = new AuthFlow(mvc, json);
        return "Bearer " + auth.accessToken(auth.login(setup.main()));
    }

    private MockHttpServletResponse as(String bearer, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", bearer)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn()
                .getResponse();
    }

    private UUID confirmedBuy(Setup setup) {
        UUID request = pendingBuy(setup, 50_000_000L);
        consumer.runOnce();
        UUID order = orderOf(request);
        driver.runOnce();
        chain.effect(txSignature(order), new RealChain.SwapEffect(-50_008_457L, 1_000_000L, 8_457L, true));
        chain.finalize(txSignature(order));
        confirmation.runOnce();
        return order;
    }

    @Test
    void anOrderOfAnotherPersonIsNotFoundOnEveryRoute() throws Exception {
        Setup owner = realBot("SIGNED");
        UUID request = pendingBuy(owner, 50_000_000L);
        consumer.runOnce();
        UUID order = orderOf(request);
        driver.runOnce();
        Setup stranger = realBot("AUTO_FULL");
        String other = bearer(stranger);

        assertThat(as(other, get("/api/v1/real/orders/" + order)).getStatus()).isEqualTo(404);
        assertThat(as(other, get("/api/v1/real/orders/" + order + "/signing-session"))
                        .getStatus())
                .isEqualTo(404);
        assertThat(as(
                                other,
                                post("/api/v1/real/orders/" + order + "/signing-session")
                                        .content("{\"signedTransactionBase64\":\"AAAA\"}"))
                        .getStatus())
                .isEqualTo(404);
        assertThat(as(other, post("/api/v1/real/orders/" + order + "/reject")).getStatus())
                .isEqualTo(404);
        assertThat(as(other, put("/api/v1/real/mode").content("{\"mode\":\"SIGNED\"}"))
                        .getStatus())
                .isEqualTo(200);
        assertThat(as(other, post("/api/v1/real/liquidations")).getStatus()).isEqualTo(202);
        assertThat(state(order)).isEqualTo("QUOTED");
    }

    @Test
    void theListAndThePnlShowOnlyThePersonOfTheToken() throws Exception {
        Setup owner = realBot("AUTO_FULL");
        UUID order = confirmedBuy(owner);
        Setup stranger = realBot("AUTO_FULL");

        JsonNode mine =
                json.readTree(as(bearer(owner), get("/api/v1/real/orders")).getContentAsString());
        JsonNode theirs =
                json.readTree(as(bearer(stranger), get("/api/v1/real/orders")).getContentAsString());
        JsonNode theirPnl =
                json.readTree(as(bearer(stranger), get("/api/v1/real/pnl")).getContentAsString());

        assertThat(mine.path("items")).hasSize(1);
        assertThat(mine.path("items").get(0).path("realOrderId").asText()).isEqualTo(order.toString());
        assertThat(theirs.path("items")).isEmpty();
        assertThat(theirPnl.path("positions")).isEmpty();
        assertThat(theirPnl.path("realizedLamports").asText()).isEqualTo("0");
    }

    @Test
    void anOrderShowsItsAmountsAsIntegerTextWithTheDecimalsOfTheMint() throws Exception {
        Setup setup = realBot("AUTO_FULL");
        UUID order = confirmedBuy(setup);

        JsonNode body = json.readTree(
                as(bearer(setup), get("/api/v1/real/orders/" + order)).getContentAsString());

        assertThat(body.path("state").asText()).isEqualTo("CONFIRMED");
        assertThat(body.path("input").path("baseUnits").asText()).isEqualTo("50000000");
        assertThat(body.path("input").path("decimals").asInt()).isEqualTo(9);
        assertThat(body.path("output").path("baseUnits").asText()).isEqualTo("1000000");
        assertThat(body.path("output").path("decimals").asInt()).isEqualTo(6);
        assertThat(body.path("feeLamports").asText()).isEqualTo("8457");
        assertThat(body.path("feePayer").asText()).isEqualTo("BOT_WALLET");
        assertThat(body.path("txSignature").asText()).isEqualTo(txSignature(order));
    }

    /**
     * 261121-paper-real-unico U2 (decision Q5 of the founder; BR2.1, BR6.1, BR7.1, BR7.4, BR10.3): the network fee of a
     * buy is never in the cost of the position; it is in its own field {@code feesLamports}, and the result is the same
     * as when the U1 put the fee into the cost: realized + unrealized - fees equals the value of the position minus
     * what the buy took from the bot wallet (50.008.457 lamports: the 50.000.000 of the swap and the 8.457 of the fee).
     */
    @Test
    void thePnlShowsThePositionOfAConfirmedBuyAndItsFeeApart() throws Exception {
        Setup setup = realBot("AUTO_FULL");
        confirmedBuy(setup);

        JsonNode pnl = json.readTree(as(bearer(setup), get("/api/v1/real/pnl")).getContentAsString());

        assertThat(pnl.path("positions")).hasSize(1);
        JsonNode position = pnl.path("positions").get(0);
        assertThat(position.path("baseUnits").asText()).isEqualTo("1000000");
        assertThat(position.path("costLamports").asText())
                .as("the cost is what the swap paid, without the network fee")
                .isEqualTo("50000000");
        assertThat(pnl.path("feesLamports").asText())
                .as("the fee of the buy is on its own")
                .isEqualTo("8457");
        assertThat(pnl.path("realizedLamports").asText()).isEqualTo("0");

        // The price of the mint is known (realBot quotes SOL at US$ 150 and the token at US$ 0,0001).
        assertThat(position.path("priceStatus").asText()).isEqualTo("KNOWN");
        assertThat(pnl.path("unrealizedStatus").asText()).isEqualTo("KNOWN");
        long value = Long.parseLong(position.path("valueLamports").asText());
        long realized = Long.parseLong(pnl.path("realizedLamports").asText());
        long unrealized = Long.parseLong(pnl.path("unrealizedLamports").asText());
        long fees = Long.parseLong(pnl.path("feesLamports").asText());
        assertThat(unrealized).isEqualTo(value - 50_000_000L);
        assertThat(Long.parseLong(position.path("unrealizedLamports").asText())).isEqualTo(unrealized);
        // The total is the one the U1 gave (cost 50.008.457 with the fee inside, no fee apart): value - 50.008.457.
        assertThat(realized + unrealized - fees)
                .as("the economic result did not change")
                .isEqualTo(value - 50_008_457L);
    }

    @Test
    void aSecondLiquidationIsAConflictAndAnUnknownModeAValidationError() throws Exception {
        Setup setup = realBot("AUTO_FULL");
        String bearer = bearer(setup);

        assertThat(as(bearer, post("/api/v1/real/liquidations")).getStatus()).isEqualTo(202);
        MockHttpServletResponse again = as(bearer, post("/api/v1/real/liquidations"));
        assertThat(again.getStatus()).isEqualTo(409);
        assertThat(again.getContentAsString()).contains("ORDER_ALREADY_IN_PROGRESS");
        assertThat(as(bearer, put("/api/v1/real/mode").content("{\"mode\":\"TURBO\"}"))
                        .getStatus())
                .isEqualTo(400);
    }

    private MockHttpServletResponse withKey(String bearer, String key, MockHttpServletRequestBuilder request)
            throws Exception {
        return mvc.perform(request.header("Authorization", bearer)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn()
                .getResponse();
    }

    /**
     * Review (Major 2), written before the code: the mutating R3 routes use the common idempotency mechanism (contract
     * line 49, project.md). The same key returns the same answer instead of a second effect or a conflict; a new key
     * while the liquidation runs is 409; the same key with other content is 409 IDEMPOTENCY_CONFLICT.
     */
    @Test
    void theSameKeyOnALiquidationReturnsTheSameAnswerAndANewKeyIsAConflict() throws Exception {
        Setup setup = realBot("AUTO_FULL");
        String bearer = bearer(setup);
        String key = UUID.randomUUID().toString();

        MockHttpServletResponse first = withKey(bearer, key, post("/api/v1/real/liquidations"));
        MockHttpServletResponse again = withKey(bearer, key, post("/api/v1/real/liquidations"));
        MockHttpServletResponse other =
                withKey(bearer, UUID.randomUUID().toString(), post("/api/v1/real/liquidations"));

        assertThat(first.getStatus()).isEqualTo(202);
        assertThat(again.getStatus()).isEqualTo(202);
        assertThat(again.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(jdbc.sql("select count(*) from real_liquidation where user_id = :u")
                        .param("u", setup.userId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(other.getStatus()).isEqualTo(409);
        assertThat(other.getContentAsString()).contains("ORDER_ALREADY_IN_PROGRESS");
    }

    @Test
    void theSameKeyOnTheModeReplaysAndWithOtherContentIsAnIdempotencyConflict() throws Exception {
        Setup setup = realBot("AUTO_FULL");
        String bearer = bearer(setup);
        String key = UUID.randomUUID().toString();

        MockHttpServletResponse first =
                withKey(bearer, key, put("/api/v1/real/mode").content("{\"mode\":\"SIGNED\"}"));
        MockHttpServletResponse again =
                withKey(bearer, key, put("/api/v1/real/mode").content("{\"mode\":\"SIGNED\"}"));
        MockHttpServletResponse changed =
                withKey(bearer, key, put("/api/v1/real/mode").content("{\"mode\":\"AUTO_EXITS\"}"));

        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(again.getStatus()).isEqualTo(200);
        assertThat(again.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(changed.getStatus()).isEqualTo(409);
        assertThat(changed.getContentAsString()).contains("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void theSameKeyOnARejectReturnsTheSameCancelledOrder() throws Exception {
        Setup setup = realBot("SIGNED");
        UUID request = pendingBuy(setup, 50_000_000L);
        consumer.runOnce();
        UUID order = orderOf(request);
        driver.runOnce();
        String bearer = bearer(setup);
        String key = UUID.randomUUID().toString();

        MockHttpServletResponse first = withKey(bearer, key, post("/api/v1/real/orders/" + order + "/reject"));
        MockHttpServletResponse again = withKey(bearer, key, post("/api/v1/real/orders/" + order + "/reject"));

        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(again.getStatus()).isEqualTo(200);
        assertThat(json.readTree(again.getContentAsString()).path("state").asText())
                .isEqualTo("CANCELLED");
        assertThat(again.getContentAsString()).isEqualTo(first.getContentAsString());
    }

    @Test
    void theFourMutatingRoutesRefuseAMissingKey() throws Exception {
        Setup setup = realBot("AUTO_FULL");
        String bearer = bearer(setup);
        UUID order = UUID.randomUUID();

        for (MockHttpServletRequestBuilder request : List.of(
                put("/api/v1/real/mode").content("{\"mode\":\"SIGNED\"}"),
                post("/api/v1/real/orders/" + order + "/signing-session")
                        .content("{\"signedTransactionBase64\":\"AAAA\"}"),
                post("/api/v1/real/orders/" + order + "/reject"),
                post("/api/v1/real/liquidations"))) {
            MockHttpServletResponse answer = mvc.perform(
                            request.header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON))
                    .andReturn()
                    .getResponse();
            assertThat(answer.getStatus()).isEqualTo(400);
        }
        assertThat(jdbc.sql("select count(*) from real_liquidation where user_id = :u")
                        .param("u", setup.userId())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void aRealBotNeedsAnActiveBotWalletAndAPaperBotStaysTheDefault() throws Exception {
        Setup setup = realBot("AUTO_FULL");
        String bearer = bearer(setup);
        var noWallet = signIn(true, true);

        MockHttpServletResponse refused = call(
                noWallet,
                post("/api/v1/bots")
                        .content("{\"templateId\":\"MOMENTUM_VOLUME\",\"name\":\"real\",\"market\":\"REAL\"}"));
        MockHttpServletResponse real = as(
                bearer,
                post("/api/v1/bots")
                        .content("{\"templateId\":\"MOMENTUM_VOLUME\",\"name\":\"real\",\"market\":\"REAL\"}"));
        MockHttpServletResponse paper =
                as(bearer, post("/api/v1/bots").content("{\"templateId\":\"MOMENTUM_VOLUME\",\"name\":\"paper\"}"));

        // 261121-paper-real-unico U1 (BR2.4, PQ-5): the inactive wallet is now 409 REAL_NOT_ELIGIBLE, never 422.
        assertThat(refused.getStatus()).isEqualTo(409);
        assertThat(refused.getContentAsString()).contains("REAL_NOT_ELIGIBLE").contains("WALLET_INACTIVE");
        assertThat(real.getStatus()).isEqualTo(201);
        assertThat(json.readTree(real.getContentAsString()).path("market").asText())
                .isEqualTo("REAL");
        assertThat(json.readTree(paper.getContentAsString()).path("market").asText())
                .isEqualTo("PAPER");
    }
}
