package com.memetrade.botwallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.ledger.api.RealEntry;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.ledger.api.RealLedger;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;

/**
 * Step 28 (loop-back 1 of Build and Test), written BEFORE the limits (team.md Testing Posture and mandatory test
 * "saque só para a carteira principal ... continua disponível", NFR1.9): the 11th consent challenge in a minute, the
 * 4th activation in a day and the 11th partial withdrawal in an hour answer {@code 429 RATE_LIMITED} with
 * {@code Retry-After} as RFC 7807; the total withdrawal and the reading of the withdrawals are never limited.
 */
@TestPropertySource(properties = "memetrade.real.enabled=true")
// U2 (BR1.6): REAL on with the simulated market sources is allowed only in the test and local profiles.
@org.springframework.test.context.ActiveProfiles("local")
class BotWalletRateLimitIT extends AbstractRealIT {

    private static final long SOL = 1_000_000_000L;
    private static final String CHALLENGE = "{\"kind\":\"ACTIVATE\",\"capLamports\":\"2000000000\"}";

    @Autowired
    private RealLedger ledger;

    private MockHttpServletResponse challenge(Person person) throws Exception {
        return call(person, post("/api/v1/real/consents/challenge").content(CHALLENGE));
    }

    private MockHttpServletResponse activate(Person person, String challengeId, String signature) throws Exception {
        return call(
                person,
                post("/api/v1/real/bot-wallet")
                        .content(json.writeValueAsString(Map.of("challengeId", challengeId, "signature", signature))));
    }

    private void activeWallet(Person person) throws Exception {
        JsonNode challenge = body(challenge(person));
        MockHttpServletResponse response = activate(
                person,
                challenge.get("challengeId").asText(),
                person.wallet().sign(challenge.get("message").asText()));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
    }

    private void deposit(Person person, long lamports) {
        UUID wallet = jdbc.sql("select bot_wallet_id from bot_wallet_account where user_id = :u")
                .param("u", person.userId())
                .query(UUID.class)
                .single();
        transactions.executeWithoutResult(status -> ledger.record(new RealEntry(
                person.tenantId(),
                wallet,
                RealEntryKind.DEPOSIT,
                lamports,
                null,
                "dep:" + UUID.randomUUID(),
                "dep:" + UUID.randomUUID())));
    }

    private MockHttpServletResponse withdraw(Person person, String body) throws Exception {
        return call(person, post("/api/v1/real/bot-wallet/withdrawals").content(body));
    }

    private void assertRateLimited(MockHttpServletResponse response, long maxRetryAfterSeconds) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(429);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(body(response).get("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(body(response).get("correlationId").asText()).isNotBlank();
        assertThat(Long.parseLong(response.getHeader("Retry-After"))).isBetween(1L, maxRetryAfterSeconds);
    }

    @Test
    void theEleventhChallengeInAMinuteIsRateLimited() throws Exception {
        Person person = signIn(true, true);
        for (int i = 0; i < 10; i++) {
            assertThat(challenge(person).getStatus()).as("challenge " + (i + 1)).isNotEqualTo(429);
        }

        assertRateLimited(challenge(person), 60);
        assertThat(challenge(signIn(true, true)).getStatus()).isEqualTo(201);
    }

    @Test
    void theFourthActivationInADayIsRateLimited() throws Exception {
        Person person = signIn(true, true);
        String forged = person.wallet().sign("not a consent");
        int walletsBefore = signer.walletsCreated();
        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse attempt = activate(person, UUID.randomUUID().toString(), forged);
            assertThat(attempt.getStatus()).as("activation " + (i + 1)).isNotEqualTo(429);
        }

        MockHttpServletResponse fourth = activate(person, UUID.randomUUID().toString(), forged);

        assertRateLimited(fourth, 86_400);
        assertThat(signer.walletsCreated()).isEqualTo(walletsBefore);
    }

    @Test
    void theEleventhPartialWithdrawalInAnHourIsRateLimitedAndTheTotalOneStillPasses() throws Exception {
        Person person = signIn(true, true);
        activeWallet(person);
        deposit(person, 2 * SOL);
        for (int i = 0; i < 10; i++) {
            MockHttpServletResponse partial = withdraw(person, "{\"amount\":{\"lamports\":\"1000000\"}}");
            assertThat(partial.getStatus()).as("partial " + (i + 1)).isNotEqualTo(429);
        }

        MockHttpServletResponse eleventh = withdraw(person, "{\"amount\":{\"lamports\":\"1000000\"}}");
        MockHttpServletResponse total = withdraw(person, "{\"amount\":{\"all\":true}}");
        MockHttpServletResponse anotherTotal = withdraw(person, "{\"amount\":{\"all\":true}}");

        assertRateLimited(eleventh, 3_600);
        assertThat(total.getStatus()).as(total.getContentAsString()).isNotEqualTo(429);
        assertThat(anotherTotal.getStatus())
                .as(anotherTotal.getContentAsString())
                .isNotEqualTo(429);
    }

    /**
     * Step 31 (review R-11), regression written before the fix: the partial withdrawal limit is consumed only by a NEW
     * request, after the idempotency check, so a network retry with the same {@code Idempotency-Key} at the limit
     * replays the stored answer instead of answering 429, and never creates a second withdrawal.
     */
    @Test
    void aRetryWithTheSameKeyAtTheLimitReplaysTheStoredAnswerInsteadOf429() throws Exception {
        Person person = signIn(true, true);
        activeWallet(person);
        deposit(person, 2 * SOL);
        String sameKey = UUID.randomUUID().toString();
        MockHttpServletResponse first = withdrawWithKey(person, sameKey, "{\"amount\":{\"lamports\":\"1000000\"}}");
        assertThat(first.getStatus()).as(first.getContentAsString()).isEqualTo(202);
        String withdrawalId = body(first).get("withdrawalId").asText();
        for (int i = 1; i < 10; i++) {
            MockHttpServletResponse partial = withdraw(person, "{\"amount\":{\"lamports\":\"1000000\"}}");
            assertThat(partial.getStatus()).as("partial " + (i + 1)).isEqualTo(202);
        }

        for (int retry = 0; retry < 3; retry++) {
            MockHttpServletResponse again = withdrawWithKey(person, sameKey, "{\"amount\":{\"lamports\":\"1000000\"}}");
            assertThat(again.getStatus()).as(again.getContentAsString()).isEqualTo(202);
            assertThat(body(again).get("withdrawalId").asText()).isEqualTo(withdrawalId);
        }

        assertRateLimited(withdraw(person, "{\"amount\":{\"lamports\":\"1000000\"}}"), 3_600);
        assertThat(jdbc.sql("select count(*) from withdrawal w join bot_wallet_account a"
                                + " on a.bot_wallet_id = w.bot_wallet_id where a.user_id = :u")
                        .param("u", person.userId())
                        .query(Long.class)
                        .single())
                .as("ten withdrawals, the retries created none")
                .isEqualTo(10L);
    }

    /** Step 31: a 429 stores nothing under its key, so the person can reuse it for the total withdrawal. */
    @Test
    void aRateLimitedPartialWithdrawalKeepsNothingUnderItsKey() throws Exception {
        Person person = signIn(true, true);
        activeWallet(person);
        deposit(person, 2 * SOL);
        for (int i = 0; i < 10; i++) {
            assertThat(withdraw(person, "{\"amount\":{\"lamports\":\"1000000\"}}")
                            .getStatus())
                    .isEqualTo(202);
        }
        String refusedKey = UUID.randomUUID().toString();

        assertRateLimited(withdrawWithKey(person, refusedKey, "{\"amount\":{\"lamports\":\"1000000\"}}"), 3_600);
        MockHttpServletResponse totalWithTheSameKey =
                withdrawWithKey(person, refusedKey, "{\"amount\":{\"all\":true}}");

        assertThat(totalWithTheSameKey.getStatus())
                .as("no 409 IDEMPOTENCY_CONFLICT: " + totalWithTheSameKey.getContentAsString())
                .isEqualTo(202);
    }

    private MockHttpServletResponse withdrawWithKey(Person person, String key, String body) throws Exception {
        return mvc.perform(post("/api/v1/real/bot-wallet/withdrawals")
                        .header("Authorization", person.bearer())
                        .header("Idempotency-Key", key)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse();
    }

    @Test
    void readingTheWithdrawalsIsNeverRateLimited() throws Exception {
        Person person = signIn(true, true);
        activeWallet(person);
        deposit(person, SOL);
        String id = body(withdraw(person, "{\"amount\":{\"lamports\":\"1000000\"}}"))
                .get("withdrawalId")
                .asText();
        for (int i = 0; i < 10; i++) {
            withdraw(person, "{\"amount\":{\"lamports\":\"1000000\"}}");
        }

        for (int i = 0; i < 30; i++) {
            assertThat(call(person, get("/api/v1/real/bot-wallet/withdrawals")).getStatus())
                    .isEqualTo(200);
            assertThat(call(person, get("/api/v1/real/bot-wallet/withdrawals/" + id))
                            .getStatus())
                    .isEqualTo(200);
        }
        assertThat(call(person, get("/api/v1/real/bot-wallet")).getStatus()).isEqualTo(200);
    }
}
