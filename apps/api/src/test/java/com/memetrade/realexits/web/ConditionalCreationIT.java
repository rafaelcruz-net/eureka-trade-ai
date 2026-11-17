package com.memetrade.realexits.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.botwallet.api.RealKillSwitchState;
import com.memetrade.realexits.AbstractRealExitsIT;
import com.memetrade.realexits.domain.AdapterKind;
import com.memetrade.riskcontrol.api.RealRiskGate;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Step 6 of the U7, written BEFORE the code (BR1, BR3, BR4.1, BR4.4, BR9.3, S1 to S12, S43, S48, U7-NFR1.4,
 * U7-NFR1.9, U7-NFR3.4): the creation of a conditional through the routes R5 of the addendum, the signing session of
 * the simulated path (the consent of the conditional signed with {@code signMessage} by the main wallet), and the
 * cancellation by the person. Tenant and person only from the token; another person's conditional is 404.
 */
@Tag("u7-exits-and-fiscal")
class ConditionalCreationIT extends AbstractRealExitsIT {

    private static final String ROUTE = "/api/v1/real/conditional-orders";

    @Autowired
    private RealKillSwitchState killSwitch;

    @Autowired
    private RealRiskGate riskGate;

    @BeforeEach
    void simulatedAdapter() {
        trigger.kind.set(AdapterKind.SIMULATED);
    }

    private MockHttpServletResponse create(Person person, String key, Map<String, Object> body) throws Exception {
        return mvc.perform(post(ROUTE)
                        .header("Authorization", person.bearer())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn()
                .getResponse();
    }

    private static Map<String, Object> oco(Setup setup) {
        return Map.of(
                "positionMint",
                setup.mint(),
                "kind",
                "OCO",
                "stopPriceUsd",
                "0.00008",
                "takeProfitPriceUsd",
                "0.00015");
    }

    private MockHttpServletResponse sign(Person person, UUID id, Map<String, Object> body) throws Exception {
        return mvc.perform(post(ROUTE + "/" + id + "/signature")
                        .header("Authorization", person.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn()
                .getResponse();
    }

    private MockHttpServletResponse cancel(Person person, UUID id) throws Exception {
        return mvc.perform(delete(ROUTE + "/" + id)
                        .header("Authorization", person.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andReturn()
                .getResponse();
    }

    private String code(MockHttpServletResponse response) throws Exception {
        return body(response).path("code").asText();
    }

    /** S1: an OCO opens a signing session with the consent message and its deadline. */
    @Test
    void s1AnOcoIsCreatedPendingSignatureWithItsSigningSession() throws Exception {
        Signed signed = signedRealBot("AUTO_EXITS", 5_000L);

        MockHttpServletResponse response =
                create(signed.person(), UUID.randomUUID().toString(), oco(signed.setup()));

        assertThat(response.getStatus()).isEqualTo(201);
        JsonNode created = body(response);
        UUID id = UUID.fromString(created.path("conditionalOrderId").asText());
        assertThat(conditionalState(id)).isEqualTo("PENDING_SIGNATURE");
        assertThat(created.path("signingSession").path("kind").asText()).isEqualTo("MESSAGE");
        assertThat(created.path("signingSession").path("message").asText())
                .contains(id.toString())
                .contains("OCO")
                .contains("0.00008")
                .contains("0.00015")
                .contains("5000");
        assertThat(created.path("signingSession").path("deadline").asText()).isNotBlank();
        assertThat(created.path("stopNotGuaranteed").asBoolean()).as("BR3.8").isTrue();
        assertThat(jdbc.sql("select amount_base_units || ':' || slippage_bps from real_conditional_order where"
                                + " conditional_order_id = :id")
                        .param("id", id)
                        .query(String.class)
                        .single())
                .as("BR3.6, BR3.7: the whole position and the slippage of the profile")
                .isEqualTo("5000:200");
    }

    /** S2: the consent signed in time opens the conditional at the provider. */
    @Test
    void s2TheConsentSignedInTimeOpensTheConditional() throws Exception {
        Signed signed = signedRealBot("AUTO_EXITS", 5_000L);
        JsonNode created = body(create(signed.person(), UUID.randomUUID().toString(), oco(signed.setup())));
        UUID id = UUID.fromString(created.path("conditionalOrderId").asText());
        String message = created.path("signingSession").path("message").asText();

        MockHttpServletResponse answer = sign(
                signed.person(),
                id,
                Map.of("signature", signed.person().wallet().sign(message)));

        assertThat(answer.getStatus()).isEqualTo(202);
        assertThat(body(answer).path("state").asText()).isEqualTo("OPEN");
        assertThat(conditionalState(id)).isEqualTo("OPEN");
    }

    @Test
    void s3WithoutACurrentPriceTheCreationIsRefused() throws Exception {
        Signed signed = signedRealBot("AUTO_EXITS", 5_000L);
        jdbc.sql("delete from market_observation where mint = :m")
                .param("m", signed.setup().mint())
                .update();

        assertThat(code(create(signed.person(), UUID.randomUUID().toString(), oco(signed.setup()))))
                .isEqualTo("PRICE_UNKNOWN");
    }

    @Test
    void s4AndS5AndS43ThePricesAreCheckedBeforeAnythingElse() throws Exception {
        Signed signed = signedRealBot("AUTO_EXITS", 5_000L);
        String mint = signed.setup().mint();

        assertThat(code(create(
                        signed.person(),
                        UUID.randomUUID().toString(),
                        Map.of(
                                "positionMint",
                                mint,
                                "kind",
                                "OCO",
                                "stopPriceUsd",
                                "0.00009",
                                "takeProfitPriceUsd",
                                "0.00008"))))
                .isEqualTo("TP_NOT_ABOVE_SL");
        assertThat(code(create(
                        signed.person(),
                        UUID.randomUUID().toString(),
                        Map.of("positionMint", mint, "kind", "STOP", "stopPriceUsd", "0.00011"))))
                .isEqualTo("TRIGGER_ON_WRONG_SIDE");
        assertThat(code(create(
                        signed.person(),
                        UUID.randomUUID().toString(),
                        Map.of("positionMint", mint, "kind", "STOP", "stopPriceUsd", "0.00004"))))
                .isEqualTo("TRIGGER_OUT_OF_BOUNDS");
        assertThat(code(create(
                        signed.person(),
                        UUID.randomUUID().toString(),
                        Map.of("positionMint", mint, "kind", "STOP", "stopPriceUsd", "8e-5"))))
                .as("a DecimalString, never a number in another notation")
                .isEqualTo("VALIDATION_FAILED");
        assertThat(trigger.calls).as("nothing reached the provider").hasValue(0);
    }

    /** S6: the barrier needs the mode "Automática nas saídas". */
    @Test
    void s6InTheSignedModeTheCreationIsRefused() throws Exception {
        Signed signed = signedRealBot("SIGNED", 5_000L);

        MockHttpServletResponse response =
                create(signed.person(), UUID.randomUUID().toString(), oco(signed.setup()));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(code(response)).isEqualTo("EXITS_MODE_REQUIRED");
    }

    /** S7: a blocked token or the kill switch on refuse the creation through the barrier. */
    @Test
    void s7ABlockedTokenOrTheKillSwitchRefuseTheCreation() throws Exception {
        Signed signed = signedRealBot("AUTO_EXITS", 5_000L);
        UUID admin = UUID.randomUUID();
        riskGate.blockToken("solana", signed.setup().mint(), "teste", admin);
        try {
            assertThat(code(create(signed.person(), UUID.randomUUID().toString(), oco(signed.setup()))))
                    .isEqualTo("TOKEN_BLOCKED");
        } finally {
            riskGate.unblockToken("solana", signed.setup().mint(), admin);
        }
        killSwitch.activate(admin, "teste das saídas");
        try {
            MockHttpServletResponse paused =
                    create(signed.person(), UUID.randomUUID().toString(), oco(signed.setup()));
            assertThat(paused.getStatus()).isEqualTo(409);
            assertThat(code(paused)).isEqualTo("REAL_PAUSED");
        } finally {
            killSwitch.deactivate(admin, "fim do teste");
        }
    }

    /** S8: one conditional that is not final per position (the partial unique index). */
    @Test
    void s8ASecondConditionalOnThePositionIsAConflict() throws Exception {
        Signed signed = signedRealBot("AUTO_EXITS", 5_000L);
        assertThat(create(signed.person(), UUID.randomUUID().toString(), oco(signed.setup()))
                        .getStatus())
                .isEqualTo(201);

        MockHttpServletResponse second = create(
                signed.person(),
                UUID.randomUUID().toString(),
                Map.of("positionMint", signed.setup().mint(), "kind", "STOP", "stopPriceUsd", "0.00009"));

        assertThat(second.getStatus()).isEqualTo(409);
        assertThat(code(second)).isEqualTo("CONDITIONAL_ALREADY_OPEN");
    }

    /** S9, BR4.4: the same key and body give the same conditional; another body with the key is a conflict. */
    @Test
    void s9TheSameIdempotencyKeyGivesTheSameConditional() throws Exception {
        Signed signed = signedRealBot("AUTO_EXITS", 5_000L);
        String key = UUID.randomUUID().toString();

        JsonNode first = body(create(signed.person(), key, oco(signed.setup())));
        JsonNode again = body(create(signed.person(), key, oco(signed.setup())));
        MockHttpServletResponse other = create(
                signed.person(),
                key,
                Map.of("positionMint", signed.setup().mint(), "kind", "STOP", "stopPriceUsd", "0.00009"));

        assertThat(again.path("conditionalOrderId")).isEqualTo(first.path("conditionalOrderId"));
        assertThat(other.getStatus()).isEqualTo(409);
        assertThat(code(other)).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(jdbc.sql("select count(*) from real_conditional_order where user_id = :u")
                        .param("u", signed.setup().userId())
                        .query(Long.class)
                        .single())
                .isOne();
    }

    @Test
    void theCreationNeedsAnIdempotencyKey() throws Exception {
        Signed signed = signedRealBot("AUTO_EXITS", 5_000L);

        MockHttpServletResponse response = mvc.perform(post(ROUTE)
                        .header("Authorization", signed.person().bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(oco(signed.setup()))))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).isBetween(400, 499);
        assertThat(conditionalCount(signed)).isZero();
    }

    /** S10, S50: the flag off refuses the creation; the list and the cancellation keep working. */
    @Test
    void s10WithTheFlagOffTheCreationIsRefusedAndReadsAndCancellationsWork() throws Exception {
        Signed signed = signedRealBot("AUTO_EXITS", 5_000L);
        UUID open = conditional(signed.setup(), "OPEN", 5_000L);
        exits.enabled.set(false);

        MockHttpServletResponse refused =
                create(signed.person(), UUID.randomUUID().toString(), oco(signed.setup()));
        MockHttpServletResponse list = mvc.perform(
                        get(ROUTE).header("Authorization", signed.person().bearer()))
                .andReturn()
                .getResponse();
        MockHttpServletResponse cancelled = cancel(signed.person(), open);

        assertThat(refused.getStatus()).isEqualTo(403);
        assertThat(code(refused)).isEqualTo("EXITS_DISABLED");
        assertThat(list.getStatus()).isEqualTo(200);
        assertThat(body(list).path("items")).hasSize(1);
        assertThat(cancelled.getStatus()).isEqualTo(202);
        assertThat(conditionalState(open)).isEqualTo("CANCELLING");
    }

    /** S11: a consent after the deadline is refused and the conditional EXPIRED. */
    @Test
    void s11AConsentAfterTheDeadlineIsRefusedAndTheConditionalExpires() throws Exception {
        Signed signed = signedRealBot("AUTO_EXITS", 5_000L);
        JsonNode created = body(create(signed.person(), UUID.randomUUID().toString(), oco(signed.setup())));
        UUID id = UUID.fromString(created.path("conditionalOrderId").asText());
        String message = created.path("signingSession").path("message").asText();
        clock.advance(Duration.ofMinutes(5));

        MockHttpServletResponse late = sign(
                signed.person(),
                id,
                Map.of("signature", signed.person().wallet().sign(message)));

        assertThat(late.getStatus()).isEqualTo(409);
        assertThat(code(late)).isEqualTo("SIGNING_DEADLINE_EXPIRED");
        assertThat(conditionalState(id)).isEqualTo("EXPIRED");
    }

    /** S12: a signature over another message is refused and nothing reaches the provider. */
    @Test
    void s12ASignatureOverAnotherMessageIsRefusedAndNothingIsSent() throws Exception {
        Signed signed = signedRealBot("AUTO_EXITS", 5_000L);
        JsonNode created = body(create(signed.person(), UUID.randomUUID().toString(), oco(signed.setup())));
        UUID id = UUID.fromString(created.path("conditionalOrderId").asText());

        MockHttpServletResponse wrong = sign(
                signed.person(),
                id,
                Map.of("signature", signed.person().wallet().sign("outra mensagem")));

        assertThat(wrong.getStatus()).isEqualTo(422);
        assertThat(code(wrong)).isEqualTo("SIGNED_MESSAGE_MISMATCH");
        assertThat(conditionalState(id)).isEqualTo("PENDING_SIGNATURE");
        assertThat(trigger.calls).hasValue(0);
    }

    /** S48: the DELETE of an OPEN conditional answers CANCELLING; of a PENDING_SIGNATURE one, CANCELLED. */
    @Test
    void s48TheDeleteOfThePersonCancels() throws Exception {
        Signed signed = signedRealBot("AUTO_EXITS", 5_000L);
        UUID open = conditional(signed.setup(), "OPEN", 5_000L);

        MockHttpServletResponse answer = cancel(signed.person(), open);

        assertThat(answer.getStatus()).isEqualTo(202);
        assertThat(body(answer).path("state").asText()).isEqualTo("CANCELLING");
        assertThat(cancelReason(open)).isEqualTo("USER");
        assertThat(cancel(signed.person(), open).getStatus())
                .as("already being cancelled: the same answer")
                .isEqualTo(202);
    }

    @Test
    void theDeleteOfAPendingSignatureCancelsAtOnceAndOfAFinalOneIsAConflict() throws Exception {
        Signed signed = signedRealBot("AUTO_EXITS", 5_000L);
        UUID pending =
                UUID.fromString(body(create(signed.person(), UUID.randomUUID().toString(), oco(signed.setup())))
                        .path("conditionalOrderId")
                        .asText());

        MockHttpServletResponse answer = cancel(signed.person(), pending);

        assertThat(body(answer).path("state").asText()).isEqualTo("CANCELLED");
        assertThat(cancel(signed.person(), pending).getStatus()).isEqualTo(409);
    }

    /** U7-NFR1.9: another person never sees, signs nor cancels the conditional (404). */
    @Test
    void anotherPersonNeverReachesTheConditional() throws Exception {
        Signed owner = signedRealBot("AUTO_EXITS", 5_000L);
        Signed other = signedRealBot("AUTO_EXITS", 5_000L);
        UUID open = conditional(owner.setup(), "OPEN", 5_000L);

        assertThat(cancel(other.person(), open).getStatus()).isEqualTo(404);
        assertThat(sign(other.person(), open, Map.of("signature", "x")).getStatus())
                .isEqualTo(404);
        assertThat(body(mvc.perform(get(ROUTE)
                                        .header("Authorization", other.person().bearer()))
                                .andReturn()
                                .getResponse())
                        .path("items"))
                .isEmpty();
        assertThat(conditionalState(open)).isEqualTo("OPEN");
    }

    /** BR8.2: the dialog reads the position and the current price in USD with its source; another person sees 0. */
    @Test
    void theQuoteShowsOnlyThePositionOfThePersonOfTheToken() throws Exception {
        Signed owner = signedRealBot("AUTO_EXITS", 5_000L);
        Signed other = signedRealBot("AUTO_EXITS", 0L);
        String route = ROUTE + "/quote?positionMint=" + owner.setup().mint();

        JsonNode mine = body(
                mvc.perform(get(route).header("Authorization", owner.person().bearer()))
                        .andReturn()
                        .getResponse());
        JsonNode theirs = body(
                mvc.perform(get(route).header("Authorization", other.person().bearer()))
                        .andReturn()
                        .getResponse());

        assertThat(mine.path("positionBaseUnits").asText()).isEqualTo("5000");
        assertThat(mine.path("priceStatus").asText()).isEqualTo("KNOWN");
        assertThat(mine.path("priceUsd").asText()).isEqualTo("0.0001");
        assertThat(mine.path("source").asText()).isNotBlank();
        assertThat(mine.path("minStopPriceUsd").asText()).isEqualTo("0.00005");
        assertThat(mine.path("maxTakeProfitPriceUsd").asText()).isEqualTo("0.001");
        assertThat(theirs.path("positionBaseUnits").asText()).isEqualTo("0");
    }

    @Test
    void theQuoteOfATokenWithoutPriceIsUnknownNeverZero() throws Exception {
        Signed signed = signedRealBot("AUTO_EXITS", 5_000L);
        jdbc.sql("delete from market_observation where mint = :m")
                .param("m", signed.setup().mint())
                .update();

        JsonNode quote = body(
                mvc.perform(get(ROUTE + "/quote?positionMint=" + signed.setup().mint())
                                .header("Authorization", signed.person().bearer()))
                        .andReturn()
                        .getResponse());

        assertThat(quote.path("priceStatus").asText()).isEqualTo("UNKNOWN");
        assertThat(quote.has("priceUsd")).isFalse();
    }

    @Test
    void withoutAPositionOfTheMintTheCreationIsRefused() throws Exception {
        Signed signed = signedRealBot("AUTO_EXITS", 0L);

        MockHttpServletResponse response =
                create(signed.person(), UUID.randomUUID().toString(), oco(signed.setup()));

        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(code(response)).isEqualTo("VALIDATION_FAILED");
    }

    /** U7-NFR3.4: the creation up to the open signing session takes at most 3 s, 20 times (maximum, not p95). */
    @Test
    void theCreationUpToTheSigningSessionTakesAtMostThreeSecondsTwentyTimes() throws Exception {
        Signed signed = signedRealBot("AUTO_EXITS", 5_000L);
        long slowest = 0;
        for (int round = 0; round < 20; round++) {
            long started = System.nanoTime();
            MockHttpServletResponse response =
                    create(signed.person(), UUID.randomUUID().toString(), oco(signed.setup()));
            slowest = Math.max(
                    slowest, Duration.ofNanos(System.nanoTime() - started).toMillis());
            assertThat(response.getStatus()).isEqualTo(201);
            cancel(
                    signed.person(),
                    UUID.fromString(body(response).path("conditionalOrderId").asText()));
        }

        assertThat(slowest).isLessThanOrEqualTo(3_000L);
    }

    private long conditionalCount(Signed signed) {
        return jdbc.sql("select count(*) from real_conditional_order where user_id = :u")
                .param("u", signed.setup().userId())
                .query(Long.class)
                .single();
    }
}
