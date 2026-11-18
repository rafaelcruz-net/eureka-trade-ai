package com.memetrade.botwallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.ledger.api.RealEntry;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.testsupport.TestWallet;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;

/**
 * Step 19, written after the routes (R2, BR1.1 to BR1.10, BR2.x, BR4.x, BR6.4, NFR6.1, team.md mandatory tests): the
 * activation with its order of checks, the consent and its single use, the TxSigner outage with the same challenge,
 * the withdrawal with the common idempotency, the isolation between accounts and the withdrawal always available.
 */
@TestPropertySource(properties = "memetrade.real.enabled=true")
// U2 (BR1.6): REAL on with the simulated market sources is allowed only in the test and local profiles.
@org.springframework.test.context.ActiveProfiles("local")
class BotWalletRoutesIT extends AbstractRealIT {

    private static final long SOL = 1_000_000_000L;

    @Autowired
    private RealLedger ledger;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meters;

    private JsonNode challenge(Person person, String capLamports) throws Exception {
        MockHttpServletResponse response = call(
                person,
                post("/api/v1/real/consents/challenge")
                        .content(json.writeValueAsString(Map.of("kind", "ACTIVATE", "capLamports", capLamports))));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return body(response);
    }

    private MockHttpServletResponse activate(Person person, JsonNode challenge, String signature) throws Exception {
        return call(
                person,
                post("/api/v1/real/bot-wallet")
                        .content(json.writeValueAsString(Map.of(
                                "challengeId", challenge.get("challengeId").asText(), "signature", signature))));
    }

    private JsonNode activeWallet(Person person) throws Exception {
        JsonNode challenge = challenge(person, "2000000000");
        MockHttpServletResponse response = activate(
                person, challenge, person.wallet().sign(challenge.get("message").asText()));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return body(response);
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

    @Test
    void theActivationChecksTheTermsV3AndTheReleaseFirst() throws Exception {
        Person withoutTerms = signIn(false, true);
        Person withoutRelease = signIn(true, false);

        MockHttpServletResponse terms = call(
                withoutTerms,
                post("/api/v1/real/consents/challenge").content("{\"kind\":\"ACTIVATE\",\"capLamports\":\"1000\"}"));
        MockHttpServletResponse release = call(
                withoutRelease,
                post("/api/v1/real/consents/challenge").content("{\"kind\":\"ACTIVATE\",\"capLamports\":\"1000\"}"));

        assertThat(terms.getStatus()).isEqualTo(403);
        assertThat(body(terms).get("code").asText()).isEqualTo("TERMS_REACCEPTANCE_REQUIRED");
        assertThat(release.getStatus()).isEqualTo(403);
        assertThat(body(release).get("code").asText()).isEqualTo("REAL_NOT_RELEASED");
    }

    @Test
    void aSignedConsentActivatesTheWalletInSignedModeWithTheChosenCap() throws Exception {
        Person person = signIn(true, true);
        JsonNode challenge = challenge(person, "2000000000");

        assertThat(challenge.get("message").asText())
                .startsWith("Eureka Trader - consentimento REAL v1\npurpose: ACTIVATE\nnetwork: LOCALNET")
                .contains("main-wallet: " + person.wallet().address())
                .contains("bot-wallet: NEW");
        JsonNode wallet = body(activate(
                person, challenge, person.wallet().sign(challenge.get("message").asText())));

        assertThat(wallet.get("mode").asText()).isEqualTo("SIGNED");
        assertThat(wallet.get("capLamports").asText()).isEqualTo("2000000000");
        assertThat(wallet.get("balance").get("totalLamports").asText()).isEqualTo("0");
        assertThat(wallet.toString()).doesNotContain("kmsKeyRef");
        MockHttpServletResponse view = call(person, get("/api/v1/real/bot-wallet"));
        assertThat(body(view).get("address").asText())
                .isEqualTo(wallet.get("address").asText());
        assertThat(jdbc.sql("select count(*) from audit_outbox")
                        .query(Long.class)
                        .single())
                .isPositive();
    }

    /** Loop-back 1 of Build and Test (NFR5.1), after the code: the gauge counts the ACTIVE bot wallets of the base. */
    @Test
    void theActiveWalletsGaugeCountsTheActivation() throws Exception {
        io.micrometer.core.instrument.Gauge active =
                meters.get("real_bot_wallets_active").gauge();
        double before = active.value();

        activeWallet(signIn(true, true));

        assertThat(active.value()).isEqualTo(before + 1);
        assertThat(active.value())
                .isEqualTo(jdbc.sql("select count(*) from bot_wallet_account where status = 'ACTIVE'")
                        .query(Long.class)
                        .single()
                        .doubleValue());
    }

    @Test
    void aSecondActivationIsRefused() throws Exception {
        Person person = signIn(true, true);
        activeWallet(person);

        MockHttpServletResponse again = call(
                person,
                post("/api/v1/real/consents/challenge").content("{\"kind\":\"ACTIVATE\",\"capLamports\":\"1000\"}"));

        assertThat(again.getStatus()).isEqualTo(409);
        assertThat(body(again).get("code").asText()).isEqualTo("BOT_WALLET_ALREADY_ACTIVE");
    }

    @Test
    void aWrongSignatureIsRefusedAndConsumesTheChallenge() throws Exception {
        Person person = signIn(true, true);
        JsonNode challenge = challenge(person, "1000");
        String message = challenge.get("message").asText();
        int walletsBefore = signer.walletsCreated();

        MockHttpServletResponse forged =
                activate(person, challenge, TestWallet.generate().sign(message));
        MockHttpServletResponse retry =
                activate(person, challenge, person.wallet().sign(message));

        assertThat(forged.getStatus()).isEqualTo(422);
        assertThat(body(forged).get("code").asText()).isEqualTo("CONSENT_INVALID");
        assertThat(retry.getStatus()).isEqualTo(422);
        assertThat(signer.walletsCreated()).isEqualTo(walletsBefore);
    }

    @Test
    void aCapAboveTenSolIsRefused() throws Exception {
        Person person = signIn(true, true);

        MockHttpServletResponse response = call(
                person,
                post("/api/v1/real/consents/challenge")
                        .content("{\"kind\":\"ACTIVATE\",\"capLamports\":\"10000000001\"}"));

        assertThat(body(response).get("code").asText()).isEqualTo("CAP_ABOVE_MAXIMUM");
    }

    @Test
    void withTheTxSignerDownTheSameChallengeLaterCreatesOneWallet() throws Exception {
        Person person = signIn(true, true);
        JsonNode challenge = challenge(person, "1000");
        String signature = person.wallet().sign(challenge.get("message").asText());
        signer.down = true;

        MockHttpServletResponse down = activate(person, challenge, signature);
        signer.down = false;
        MockHttpServletResponse later = activate(person, challenge, signature);

        assertThat(down.getStatus()).isEqualTo(503);
        assertThat(later.getStatus()).isEqualTo(201);
        assertThat(jdbc.sql("select count(*) from bot_wallet_account where user_id = :u")
                        .param("u", person.userId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void aWithdrawalIsReservedOnceForTheSameIdempotencyKey() throws Exception {
        Person person = signIn(true, true);
        activeWallet(person);
        deposit(person, 2 * SOL);
        String key = UUID.randomUUID().toString();

        MockHttpServletResponse first = withdraw(person, key, "{\"amount\":{\"lamports\":\"500000000\"}}");
        MockHttpServletResponse again = withdraw(person, key, "{\"amount\":{\"lamports\":\"500000000\"}}");
        MockHttpServletResponse conflict = withdraw(person, key, "{\"amount\":{\"lamports\":\"400000000\"}}");

        assertThat(first.getStatus()).as(first.getContentAsString()).isEqualTo(202);
        assertThat(body(first).get("state").asText()).isEqualTo("REQUESTED");
        assertThat(body(again).get("withdrawalId")).isEqualTo(body(first).get("withdrawalId"));
        assertThat(conflict.getStatus()).isEqualTo(409);
        JsonNode wallet = body(call(person, get("/api/v1/real/bot-wallet")));
        assertThat(wallet.get("balance").get("reservedLamports").asText()).isEqualTo("500005000");
    }

    @Test
    void aWithdrawalAboveTheFreeBalanceIsRefusedWithTheMaximum() throws Exception {
        Person person = signIn(true, true);
        activeWallet(person);
        deposit(person, SOL);

        MockHttpServletResponse refused =
                withdraw(person, UUID.randomUUID().toString(), "{\"amount\":{\"lamports\":\"990000000\"}}");

        assertThat(refused.getStatus()).isEqualTo(422);
        assertThat(body(refused).get("code").asText()).isEqualTo("INSUFFICIENT_FREE_BALANCE");
        assertThat(body(refused).get("detail").asText()).contains("969995000");
    }

    @Test
    void aWithdrawalHasNoDestinationField() throws Exception {
        Person person = signIn(true, true);
        activeWallet(person);
        deposit(person, SOL);

        MockHttpServletResponse refused = withdraw(
                person,
                UUID.randomUUID().toString(),
                "{\"amount\":{\"lamports\":\"1000\"},\"destination\":\""
                        + TestWallet.generate().address() + "\"}");

        assertThat(refused.getStatus()).isEqualTo(400);
    }

    @Test
    void aPausedWalletStillWithdrawsAndOnlyThePersonsOwnPauseIsResumed() throws Exception {
        Person person = signIn(true, true);
        activeWallet(person);
        deposit(person, SOL);

        MockHttpServletResponse paused =
                call(person, patch("/api/v1/real/bot-wallet/preferences").content("{\"paused\":true}"));
        MockHttpServletResponse withdrawal =
                withdraw(person, UUID.randomUUID().toString(), "{\"amount\":{\"all\":true}}");
        MockHttpServletResponse resumed = call(person, post("/api/v1/real/bot-wallet/resume"));
        MockHttpServletResponse notPaused = call(person, post("/api/v1/real/bot-wallet/resume"));

        assertThat(body(paused).get("pausedReason").asText()).isEqualTo("USER");
        assertThat(withdrawal.getStatus()).isEqualTo(202);
        assertThat(body(withdrawal).get("kind").asText()).isEqualTo("TOTAL");
        assertThat(body(resumed).get("pausedReason").asText()).isEqualTo("NONE");
        assertThat(body(notPaused).get("code").asText()).isEqualTo("REAL_NOT_PAUSED");
    }

    @Test
    void loweringTheCapIsImmediateAndRaisingWithoutConsentIsRefused() throws Exception {
        Person person = signIn(true, true);
        activeWallet(person);

        MockHttpServletResponse lowered =
                call(person, patch("/api/v1/real/bot-wallet/preferences").content("{\"capLamports\":\"1000000000\"}"));
        MockHttpServletResponse raised =
                call(person, patch("/api/v1/real/bot-wallet/preferences").content("{\"capLamports\":\"3000000000\"}"));

        assertThat(body(lowered).get("capLamports").asText()).isEqualTo("1000000000");
        assertThat(signer.lowered).containsValue(1_000_000_000L);
        assertThat(body(raised).get("code").asText()).isEqualTo("CONSENT_INVALID");
    }

    @Test
    void aWithdrawalOfAnotherAccountIsNotFound() throws Exception {
        Person owner = signIn(true, true);
        Person other = signIn(true, true);
        activeWallet(owner);
        activeWallet(other);
        deposit(owner, SOL);
        String id = body(withdraw(owner, UUID.randomUUID().toString(), "{\"amount\":{\"lamports\":\"1000000\"}}"))
                .get("withdrawalId")
                .asText();

        MockHttpServletResponse foreign = call(other, get("/api/v1/real/bot-wallet/withdrawals/" + id));
        MockHttpServletResponse own = call(owner, get("/api/v1/real/bot-wallet/withdrawals/" + id));

        assertThat(foreign.getStatus()).isEqualTo(404);
        assertThat(foreign.getContentAsString()).doesNotContain(id);
        assertThat(own.getStatus()).isEqualTo(200);
    }

    @Test
    void withoutTheNewAcceptanceOnlyTheReadAndTheWithdrawalRoutesAnswer() throws Exception {
        Person person = signIn(true, true);
        activeWallet(person);
        // A new version of the terms the person did not accept yet: only the test superuser removes the acceptance.
        connect(SUPERUSER)
                .sql("delete from terms_acceptance where user_id = :u and terms_version <> 'v3'")
                .param("u", person.userId())
                .update();

        MockHttpServletResponse read = call(person, get("/api/v1/real/bot-wallet"));
        MockHttpServletResponse list = call(person, get("/api/v1/real/bot-wallet/withdrawals"));
        MockHttpServletResponse withdraw =
                withdraw(person, UUID.randomUUID().toString(), "{\"amount\":{\"lamports\":\"1\"}}");
        MockHttpServletResponse deposits = call(person, get("/api/v1/real/bot-wallet/deposits"));
        MockHttpServletResponse preferences =
                call(person, patch("/api/v1/real/bot-wallet/preferences").content("{\"paused\":true}"));

        assertThat(read.getStatus()).isEqualTo(200);
        assertThat(list.getStatus()).isEqualTo(200);
        assertThat(withdraw.getStatus()).isNotEqualTo(403);
        assertThat(body(deposits).get("code").asText()).isEqualTo("TERMS_REACCEPTANCE_REQUIRED");
        assertThat(body(preferences).get("code").asText()).isEqualTo("TERMS_REACCEPTANCE_REQUIRED");
    }

    private MockHttpServletResponse withdraw(Person person, String key, String body) throws Exception {
        return mvc.perform(post("/api/v1/real/bot-wallet/withdrawals")
                        .header("Authorization", person.bearer())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse();
    }
}
