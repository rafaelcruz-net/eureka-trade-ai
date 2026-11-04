package com.memetrade.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.memetrade.audit.api.AuditActions;
import com.memetrade.testsupport.AbstractU3IT;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.OutboxEvents;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestInvites;
import com.memetrade.testsupport.TestWallet;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Passo 10 (F5; BR3.1 to BR3.9; NFR4.1, NFR5.1, NFR6.8, NFR6.9): the account is created in the verification with an
 * invitation, all together, or refused with the reason of the FIRST failed check of BR3.2, with the challenge consumed
 * and exactly one LOGIN_REJECTED, nothing created and the invitation unchanged. Technical failures are in
 * {@code InviteVerificationFailureIT}; concurrency in {@code InviteConcurrencyIT}.
 */
class InviteVerificationIT extends AbstractU3IT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    private AuthFlow auth;

    @BeforeEach
    void setUp() {
        auth = new AuthFlow(mvc, json);
    }

    @Test
    void aValidInvitationCreatesPersonWalletUsedInvitationAcceptanceEventAndSessionTogether() throws Exception {
        TestInvites.Created invite = TestInvites.pending(jdbc).insert();
        TestWallet wallet = TestWallet.generate();

        MockHttpServletResponse response = auth.createAccount(wallet, invite.code());

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode session = json.readTree(response.getContentAsString());
        assertThat(session.get("role").asText()).isEqualTo("BETA_USER");
        assertThat(response.getHeader("Set-Cookie")).startsWith("mt_refresh=");
        Map<String, Object> user =
                jdbc.sql("""
                        select u.user_id, u.tenant_id, u.role, u.status, w.provider
                        from wallet w join app_user u on u.user_id = w.user_id where w.address = :a
                        """).param("a", wallet.address()).query().singleRow();
        UUID userId = (UUID) user.get("user_id");
        assertThat(user)
                .containsEntry("tenant_id", invite.tenantId())
                .containsEntry("role", "BETA_USER")
                .containsEntry("status", "ACTIVE")
                .containsEntry("provider", "SOLFLARE");
        assertThat(inviteRow(invite.inviteId()))
                .containsEntry("status", "USED")
                .containsEntry("used_by_user_id", userId);
        Map<String, Object> acceptance = jdbc.sql(
                        "select terms_version, invite_id, tenant_id from terms_acceptance where user_id = :u")
                .param("u", userId)
                .query()
                .singleRow();
        assertThat(acceptance)
                .containsEntry("terms_version", "v1")
                .containsEntry("invite_id", invite.inviteId())
                .containsEntry("tenant_id", invite.tenantId());
        assertThat(OutboxEvents.count(jdbc, AuditActions.ACCOUNT_CREATED, "invite:" + invite.inviteId()))
                .isEqualTo(1);
        String event = OutboxEvents.payloads(jdbc, AuditActions.ACCOUNT_CREATED).stream()
                .filter(payload -> payload.contains(invite.inviteId().toString()))
                .findFirst()
                .orElseThrow();
        JsonNode details = json.readTree(event).get("details");
        assertThat(details.get("termsVersion").asText()).isEqualTo("v1");
        assertThat(details.get("acceptedAt").asText()).isEqualTo(clock.instant().toString());
        assertThat(event).doesNotContain(invite.code());
        assertThat(OutboxEvents.reasonsForWallet(jdbc, AuditActions.LOGIN_REJECTED, wallet.address()))
                .isEmpty();
        assertThat(sessionsOf(userId)).isEqualTo(1);
    }

    @Test
    void anUnknownOrMalformedCodeIs404InviteNotFoundAfterTheSignatureAndConsumesTheChallenge() throws Exception {
        TestWallet unknown = TestWallet.generate();
        TestWallet malformed = TestWallet.generate();

        Refusal first = refuse(unknown, TestInvites.newCode(), "v1");
        Refusal second = refuse(malformed, "not-a-code", "v1");

        for (Refusal refusal : List.of(first, second)) {
            assertThat(refusal.status()).isEqualTo(404);
            assertThat(refusal.code()).isEqualTo("INVITE_NOT_FOUND");
            assertThat(refusal.audited()).containsExactly("INVITE_NOT_FOUND");
        }
        assertThat(first.problem().get("detail")).isEqualTo(second.problem().get("detail"));
        assertNoAccount(unknown);
        assertNoAccount(malformed);
    }

    @Test
    void anExpiredRevokedOrUsedInvitationIs422InviteInvalidWithTheReasonAndNothingChanges() throws Exception {
        TestInvites.Created expired = TestInvites.pending(jdbc)
                .expiresAt(clock.instant().minusSeconds(1))
                .insert();
        TestInvites.Created revoked =
                TestInvites.pending(jdbc).revokedAt(clock.instant()).insert();
        TestInvites.Created used = TestInvites.pending(jdbc).insert();
        assertThat(auth.createAccount(TestWallet.generate(), used.code()).getStatus())
                .isEqualTo(200);

        assertInvalid(refuse(TestWallet.generate(), expired.code(), "v1"), "EXPIRED", "INVITE_EXPIRED");
        assertInvalid(refuse(TestWallet.generate(), revoked.code(), "v1"), "REVOKED", "INVITE_REVOKED");
        assertInvalid(refuse(TestWallet.generate(), used.code(), "v1"), "USED", "INVITE_USED");
        assertThat(inviteRow(expired.inviteId())).containsEntry("status", "PENDING");
        assertThat(inviteRow(revoked.inviteId())).containsEntry("status", "REVOKED");
    }

    @Test
    void theInvitationIsAcceptedAtExpiresAtAndRefusedOneSecondLater() throws Exception {
        TestInvites.Created atLimit = TestInvites.pending(jdbc)
                .expiresAt(clock.instant().plus(Duration.ofHours(3)))
                .insert();
        TestInvites.Created afterLimit = TestInvites.pending(jdbc)
                .expiresAt(clock.instant().plus(Duration.ofHours(3)))
                .insert();
        TestWallet first = TestWallet.generate();
        TestWallet second = TestWallet.generate();
        // Both challenges are issued one minute before the limit, so they are still inside their own 5 minutes.
        clock.set(atLimit.expiresAt().minus(Duration.ofMinutes(1)));
        String firstMessage = auth.challenge(first).get("message").asText();
        String secondMessage = auth.challenge(second).get("message").asText();

        clock.set(atLimit.expiresAt());
        MockHttpServletResponse exact =
                auth.verifyWithInvite(first, firstMessage, first.sign(firstMessage), atLimit.code(), "v1");
        clock.set(afterLimit.expiresAt().plusSeconds(1));
        Refusal late = refuse(second, afterLimit.code(), "v1", secondMessage);

        assertThat(exact.getStatus()).as("accepted at expiresAt, inclusive").isEqualTo(200);
        assertThat(inviteRow(atLimit.inviteId())).containsEntry("status", "USED");
        assertInvalid(late, "EXPIRED", "INVITE_EXPIRED");
    }

    @Test
    void theAdministratorWalletWithAnInvitationIs403AndTheInvitationStaysPending() throws Exception {
        TestInvites.Created invite = TestInvites.pending(jdbc).insert();

        Refusal refusal = refuse(ADMIN_WALLET, invite.code(), "v1");

        assertThat(refusal.status()).isEqualTo(403);
        assertThat(refusal.code()).isEqualTo("FORBIDDEN_ROLE");
        assertThat(refusal.audited()).containsExactly("ADMIN_WALLET");
        assertThat(inviteRow(invite.inviteId())).containsEntry("status", "PENDING");
        assertThat(jdbc.sql("""
                                select count(*) from audit_outbox where payload->>'action' = 'REQUEST_FORBIDDEN'
                                  and payload->>'correlationId' = :c
                                """)
                        .param("c", refusal.problem().get("correlationId").asText())
                        .query(Long.class)
                        .single())
                .as("one event per refusal: LOGIN_REJECTED only")
                .isZero();
    }

    @Test
    void aWalletThatAlreadyHasAnAccountIs409AndTheInvitationStaysUsableByAnotherWallet() throws Exception {
        TestWallet linked = TestWallet.generate();
        TestAccounts.betaUser(jdbc, linked.address());
        TestInvites.Created invite = TestInvites.pending(jdbc).insert();

        Refusal refusal = refuse(linked, invite.code(), "v1");

        assertThat(refusal.status()).isEqualTo(409);
        assertThat(refusal.code()).isEqualTo("WALLET_ALREADY_LINKED");
        assertThat(refusal.audited()).containsExactly("WALLET_ALREADY_LINKED");
        assertThat(inviteRow(invite.inviteId())).containsEntry("status", "PENDING");
        assertThat(auth.createAccount(TestWallet.generate(), invite.code()).getStatus())
                .isEqualTo(200);
    }

    @Test
    void anInvitationOfASuspendedTenantIs422TenantInactive() throws Exception {
        UUID suspended = TestInvites.tenant(jdbc, "ACTIVE");
        TestInvites.Created invite = TestInvites.pending(jdbc).tenant(suspended).insert();
        TestAccounts.setTenantStatus(jdbc, suspended, "SUSPENDED");
        TestWallet wallet = TestWallet.generate();

        assertInvalid(refuse(wallet, invite.code(), "v1"), "TENANT_INACTIVE", "TENANT_INACTIVE");
        assertNoAccount(wallet);
    }

    @Test
    void missingOrOutdatedTermsAre422TermsNotAccepted() throws Exception {
        TestInvites.Created invite = TestInvites.pending(jdbc).insert();

        for (String version : new String[] {null, "v0", "V1", ""}) {
            TestWallet wallet = TestWallet.generate();
            Refusal refusal = refuse(wallet, invite.code(), version);
            assertThat(refusal.status()).as(String.valueOf(version)).isEqualTo(422);
            assertThat(refusal.code()).isEqualTo("TERMS_NOT_ACCEPTED");
            assertThat(refusal.audited()).containsExactly("TERMS_NOT_ACCEPTED");
            assertNoAccount(wallet);
        }
        assertThat(inviteRow(invite.inviteId())).containsEntry("status", "PENDING");
    }

    @Test
    void anUnusableInvitationWithoutTermsGetsTheReasonOfTheInvitation() throws Exception {
        TestInvites.Created revoked =
                TestInvites.pending(jdbc).revokedAt(clock.instant()).insert();

        assertThat(refuse(TestWallet.generate(), TestInvites.newCode(), null).code())
                .isEqualTo("INVITE_NOT_FOUND");
        assertInvalid(refuse(TestWallet.generate(), revoked.code(), null), "REVOKED", "INVITE_REVOKED");
    }

    @Test
    void theSignatureOfARefusedVerificationNeverBecomesValidAgain() throws Exception {
        TestInvites.Created invite = TestInvites.pending(jdbc).insert();
        TestWallet linked = TestWallet.generate();
        TestAccounts.betaUser(jdbc, linked.address());
        TestWallet[] wallets = {TestWallet.generate(), TestWallet.generate(), linked};
        String[] codes = {TestInvites.newCode(), invite.code(), invite.code()};
        String[] versions = {"v1", null, "v1"};

        for (int i = 0; i < wallets.length; i++) {
            String message = auth.challenge(wallets[i]).get("message").asText();
            String signature = wallets[i].sign(message);
            auth.verifyWithInvite(wallets[i], message, signature, codes[i], versions[i]);

            MockHttpServletResponse replay = auth.verifyWithInvite(wallets[i], message, signature, invite.code(), "v1");

            assertThat(replay.getStatus()).as("replay %d", i).isEqualTo(401);
            JsonNode problem = json.readTree(replay.getContentAsString());
            assertThat(problem.get("code").asText()).isEqualTo("LOGIN_CHALLENGE_REJECTED");
            assertThat(problem.get("reason").asText()).isEqualTo("NONCE_ALREADY_USED");
        }
        assertThat(inviteRow(invite.inviteId())).containsEntry("status", "PENDING");
    }

    @Test
    void aTenantIdInTheBodyIsRefusedAsAnUndeclaredFieldBeforeTheChallengeIsTouched() throws Exception {
        TestInvites.Created invite = TestInvites.pending(jdbc).insert();
        TestWallet wallet = TestWallet.generate();
        JsonNode challenge = auth.challenge(wallet);
        String message = challenge.get("message").asText();
        ObjectNode body = json.createObjectNode();
        body.put("walletAddress", wallet.address());
        body.put("message", message);
        body.put("signature", wallet.sign(message));
        body.put("provider", "PHANTOM");
        body.put("inviteCode", invite.code());
        body.put("acceptedTermsVersion", "v1");
        body.put("tenantId", UUID.randomUUID().toString());

        MockHttpServletResponse response = mvc.perform(post("/api/v1/auth/verify")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("VALIDATION_FAILED").contains("tenantId");
        assertThat(challengeState(challenge.get("nonce").asText())).isEqualTo("ISSUED");
        body.remove("tenantId");
        body.put("acceptedTermsVersion", "v".repeat(33));
        assertThat(mvc.perform(post("/api/v1/auth/verify")
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(body)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        assertThat(inviteRow(invite.inviteId())).containsEntry("status", "PENDING");
    }

    @Test
    void withoutAnInvitationTheU1RulesHoldAndAcceptedTermsAloneAreIgnored() throws Exception {
        TestWallet unknown = TestWallet.generate();
        TestWallet member = TestWallet.generate();
        TestAccounts.betaUser(jdbc, member.address());

        String unknownMessage = auth.challenge(unknown).get("message").asText();
        MockHttpServletResponse refused =
                auth.verifyWithInvite(unknown, unknownMessage, unknown.sign(unknownMessage), null, "v1");
        String memberMessage = auth.challenge(member).get("message").asText();
        MockHttpServletResponse accepted =
                auth.verifyWithInvite(member, memberMessage, member.sign(memberMessage), null, "v0");

        assertThat(refused.getStatus()).isEqualTo(401);
        assertThat(json.readTree(refused.getContentAsString()).get("reason").asText())
                .isEqualTo("UNKNOWN_ACCOUNT");
        assertThat(accepted.getStatus()).isEqualTo(200);
        assertNoAccount(unknown);
    }

    @Test
    void anInvitationCannotBeUsedByAnotherSignerAndAWrongSignatureConsumesNothingOfTheInvitation() throws Exception {
        TestInvites.Created invite = TestInvites.pending(jdbc).insert();
        TestWallet wallet = TestWallet.generate();
        TestWallet other = TestWallet.generate();
        String message = auth.challenge(wallet).get("message").asText();

        MockHttpServletResponse response =
                auth.verifyWithInvite(wallet, message, other.sign(message), invite.code(), "v1");

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(json.readTree(response.getContentAsString()).get("reason").asText())
                .isEqualTo("WRONG_SIGNER");
        assertThat(inviteRow(invite.inviteId())).containsEntry("status", "PENDING");
        assertNoAccount(wallet);
    }

    // ----- helpers -----

    private record Refusal(int status, String code, JsonNode problem, List<String> audited) {}

    private Refusal refuse(TestWallet wallet, String code, String version) throws Exception {
        return refuse(
                wallet, code, version, auth.challenge(wallet).get("message").asText());
    }

    private Refusal refuse(TestWallet wallet, String code, String version, String message) throws Exception {
        MockHttpServletResponse response = auth.verifyWithInvite(wallet, message, wallet.sign(message), code, version);
        JsonNode problem = json.readTree(response.getContentAsString());
        String nonce = message.lines()
                .filter(line -> line.startsWith("Nonce: "))
                .findFirst()
                .orElseThrow()
                .substring("Nonce: ".length());
        assertThat(challengeState(nonce))
                .as("the challenge is consumed by the refusal")
                .isEqualTo("CONSUMED");
        return new Refusal(
                response.getStatus(),
                problem.path("code").asText(),
                problem,
                OutboxEvents.reasonsForWallet(jdbc, AuditActions.LOGIN_REJECTED, wallet.address()));
    }

    private void assertInvalid(Refusal refusal, String reason, String audited) {
        assertThat(refusal.status()).isEqualTo(422);
        assertThat(refusal.code()).isEqualTo("INVITE_INVALID");
        assertThat(refusal.problem().get("reason").asText()).isEqualTo(reason);
        assertThat(refusal.audited()).containsExactly(audited);
    }

    private void assertNoAccount(TestWallet wallet) {
        assertThat(jdbc.sql("select count(*) from wallet where address = :a")
                        .param("a", wallet.address())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    private Map<String, Object> inviteRow(UUID inviteId) {
        return jdbc.sql("select status, used_by_user_id from invite where invite_id = :id")
                .param("id", inviteId)
                .query()
                .singleRow();
    }

    private String challengeState(String nonce) {
        return jdbc.sql("select state from login_challenge where nonce = :n")
                .param("n", nonce)
                .query(String.class)
                .single();
    }

    private long sessionsOf(UUID userId) {
        return jdbc.sql("select count(*) from user_session where user_id = :u")
                .param("u", userId)
                .query(Long.class)
                .single();
    }
}
