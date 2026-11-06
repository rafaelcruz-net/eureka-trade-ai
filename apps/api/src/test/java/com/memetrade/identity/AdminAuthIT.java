package com.memetrade.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.testsupport.AbstractU8IT;
import com.memetrade.testsupport.AdminAuthFlow;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.OutboxEvents;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Passo 10 of U8 (BR1.1 to BR1.6, AC1.2.6, NFR6.1, NFR6.2; security-design.md "Entrada do administrador", Q4, R-05;
 * team.md "login sem reutilização de assinatura"), with wallets generated in test code only: a listed wallet enters
 * with a session of 15 minutes / 8 hours and its own cookie; every refusal is generic and audited with the exact reason;
 * nonces and cookies never serve twice; the limits are per IP and per (address, IP); the login signature authorises
 * nothing else.
 */
class AdminAuthIT extends AbstractU8IT {

    private AdminAuthFlow admin;

    @BeforeEach
    void flow() {
        admin = new AdminAuthFlow(mvc, json);
    }

    @Test
    void aListedWalletEntersWithASessionOfFifteenMinutesAndEightHours() throws Exception {
        TestWallet wallet = listedWallet();
        Instant now = clock.instant();

        MockHttpServletResponse response = admin.login(wallet);

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode session = body(response);
        assertThat(session.get("role").asText()).isEqualTo("ADMIN");
        assertThat(Instant.parse(session.get("accessTokenExpiresAt").asText()))
                .isEqualTo(now.plus(Duration.ofMinutes(15)));
        assertThat(Instant.parse(session.get("sessionExpiresAt").asText())).isEqualTo(now.plus(Duration.ofHours(8)));
        assertThat(session.get("walletMasked").asText())
                .startsWith(wallet.address().substring(0, 4))
                .doesNotContain(wallet.address());
        assertThat(response.getHeader("Set-Cookie"))
                .startsWith("mt_admin_refresh=")
                .contains("Path=/api/v1/admin/auth/refresh")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Strict");
        assertThat(jdbc.sql("""
                                select u.role || ':' || u.tenant_id from wallet w join app_user u on u.user_id = w.user_id
                                where w.address = :address
                                """)
                        .param("address", wallet.address())
                        .query(String.class)
                        .single())
                .isEqualTo("ADMIN:" + SYSTEM_TENANT);
        assertThat(OutboxEvents.payloads(jdbc, "ADMIN_LOGIN"))
                .anyMatch(
                        payload -> payload.contains(session.get("walletMasked").asText()));
        assertThat(mvc.perform(get("/api/v1/admin/kill-switch")
                                .header("Authorization", "Bearer " + admin.accessToken(response)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    void theSecondEntryReusesTheAccount() throws Exception {
        TestWallet wallet = listedWallet();

        admin.login(wallet);
        admin.login(wallet);

        assertThat(jdbc.sql("select count(*) from wallet where address = :address")
                        .param("address", wallet.address())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    /** R-05: the challenge is issued for any valid address, with the domain of the administrator app. */
    @Test
    void theChallengeAnswersForAnyAddressWithTheAdministratorDomain() throws Exception {
        TestWallet unknown = TestWallet.generate();

        MockHttpServletResponse response = admin.challengeResponse(unknown.address(), "127.0.0.1");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(body(response).get("domain").asText()).isEqualTo("localhost:3001");
        assertThat(body(response).get("message").asText()).contains("URI: http://localhost:3001");
        assertThat(admin.challengeResponse("not-base58-0OIl", "127.0.0.1").getStatus())
                .isEqualTo(400);
    }

    /** BR1.1 with R-05: a wallet outside the list gets the generic 403; the reason goes only to the event. */
    @Test
    void aWalletOutsideTheListIsRefusedGenerically() throws Exception {
        TestWallet stranger = TestWallet.generate();

        MockHttpServletResponse refused = admin.login(stranger);

        assertThat(refused.getStatus()).isEqualTo(403);
        assertThat(body(refused).get("code").asText()).isEqualTo("FORBIDDEN_ROLE");
        assertThat(OutboxEvents.reasonsForWallet(jdbc, "LOGIN_REJECTED", stranger.address()))
                .containsExactly("NOT_ADMIN_WALLET");
        assertThat(sessionsOf(stranger)).isZero();
    }

    /** BR1.2: a listed wallet that belongs to a guest gets THE SAME 403; the guest account is never changed. */
    @Test
    void aGuestWalletIsRefusedWithTheSameAnswerAndStaysAGuest() throws Exception {
        TestWallet guest = TestWallet.generate();
        TestAccounts.betaUser(jdbc, guest.address());
        adminWallets.add(guest.address());
        TestWallet stranger = TestWallet.generate();

        MockHttpServletResponse refused = admin.login(guest);
        MockHttpServletResponse other = admin.login(stranger);

        assertThat(refused.getStatus()).isEqualTo(403);
        assertThat(body(refused).get("detail")).isEqualTo(body(other).get("detail"));
        assertThat(body(refused).has("reason")).isFalse();
        assertThat(OutboxEvents.reasonsForWallet(jdbc, "LOGIN_REJECTED", guest.address()))
                .containsExactly("WALLET_IS_GUEST");
        assertThat(jdbc.sql("""
                                select u.role from wallet w join app_user u on u.user_id = w.user_id
                                where w.address = :address
                                """)
                        .param("address", guest.address())
                        .query(String.class)
                        .single())
                .isEqualTo("BETA_USER");
    }

    /** team.md: a reused nonce is refused (401) and recorded. */
    @Test
    void aReusedNonceIsRefused() throws Exception {
        TestWallet wallet = listedWallet();
        String message = admin.challenge(wallet).get("message").asText();
        String signature = wallet.sign(message);
        assertThat(admin.verify(wallet, message, signature).getStatus()).isEqualTo(200);

        MockHttpServletResponse again = admin.verify(wallet, message, signature);

        assertThat(again.getStatus()).isEqualTo(401);
        assertThat(OutboxEvents.reasonsForWallet(jdbc, "LOGIN_REJECTED", wallet.address()))
                .containsExactly("NONCE_INVALID");
    }

    /** team.md: an expired challenge (5 minutes) is refused. */
    @Test
    void anExpiredChallengeIsRefused() throws Exception {
        TestWallet wallet = listedWallet();
        String message = admin.challenge(wallet).get("message").asText();
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));

        assertThat(admin.verify(wallet, message, wallet.sign(message)).getStatus())
                .isEqualTo(401);
    }

    @Test
    void anInvalidSignatureIsRefused() throws Exception {
        TestWallet wallet = listedWallet();
        TestWallet other = TestWallet.generate();
        String message = admin.challenge(wallet).get("message").asText();

        MockHttpServletResponse refused = admin.verify(wallet, message, other.sign(message));

        assertThat(refused.getStatus()).isEqualTo(401);
        assertThat(OutboxEvents.reasonsForWallet(jdbc, "LOGIN_REJECTED", wallet.address()))
                .containsExactly("SIGNATURE_INVALID");
    }

    /** security-design.md: a guest challenge never opens an administrator session, and vice versa. */
    @Test
    void eachChallengeServesOnlyItsOwnEntry() throws Exception {
        TestWallet wallet = listedWallet();
        AuthFlow guestFlow = new AuthFlow(mvc, json);
        String guestMessage = guestFlow.challenge(wallet).get("message").asText();
        String adminMessage = admin.challenge(wallet).get("message").asText();

        MockHttpServletResponse adminWithGuestChallenge = admin.verify(wallet, guestMessage, wallet.sign(guestMessage));
        TestWallet guest = TestWallet.generate();
        TestAccounts.betaUser(jdbc, guest.address());
        String otherAdminMessage = admin.challenge(guest).get("message").asText();
        MockHttpServletResponse guestWithAdminChallenge =
                guestFlow.verify(guest, otherAdminMessage, guest.sign(otherAdminMessage));

        assertThat(adminWithGuestChallenge.getStatus()).isEqualTo(401);
        assertThat(guestWithAdminChallenge.getStatus()).isEqualTo(401);
        assertThat(admin.verify(wallet, adminMessage, wallet.sign(adminMessage)).getStatus())
                .isEqualTo(200);
    }

    /** BR1.3: the refresh rotates the cookie and never extends the 8 hours; a reused cookie ends the session. */
    @Test
    void theRefreshRotatesNeverExtendsAndAReusedCookieEndsTheSession() throws Exception {
        TestWallet wallet = listedWallet();
        MockHttpServletResponse login = admin.login(wallet);
        Instant sessionEnd = Instant.parse(body(login).get("sessionExpiresAt").asText());
        String firstCookie = AdminAuthFlow.refreshToken(login);
        clock.advance(Duration.ofHours(7).plusMinutes(55));

        MockHttpServletResponse renewed = admin.refresh(firstCookie);

        assertThat(renewed.getStatus()).isEqualTo(200);
        assertThat(Instant.parse(body(renewed).get("sessionExpiresAt").asText()))
                .isEqualTo(sessionEnd);
        assertThat(Instant.parse(body(renewed).get("accessTokenExpiresAt").asText()))
                .isEqualTo(sessionEnd);
        String secondCookie = AdminAuthFlow.refreshToken(renewed);
        assertThat(secondCookie).isNotEqualTo(firstCookie);

        MockHttpServletResponse reused = admin.refresh(firstCookie);

        assertThat(reused.getStatus()).isEqualTo(401);
        assertThat(admin.refresh(secondCookie).getStatus()).isEqualTo(401);
    }

    /** BR1.4: a wallet removed from the list loses the access at the next refresh, with ADMIN_SESSION_REVOKED. */
    @Test
    void aWalletRemovedFromTheListLosesTheAccessAtTheNextRefresh() throws Exception {
        TestWallet wallet = listedWallet();
        MockHttpServletResponse login = admin.login(wallet);
        int revokedBefore = OutboxEvents.payloads(jdbc, "ADMIN_SESSION_REVOKED").size();
        adminWallets.remove(wallet.address());

        MockHttpServletResponse refused = admin.refresh(AdminAuthFlow.refreshToken(login));

        assertThat(refused.getStatus()).isEqualTo(401);
        assertThat(OutboxEvents.payloads(jdbc, "ADMIN_SESSION_REVOKED")).hasSize(revokedBefore + 1);
        assertThat(mvc.perform(get("/api/v1/admin/kill-switch")
                                .header("Authorization", "Bearer " + admin.accessToken(login)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
        assertThat(jdbc.sql("select count(*) from wallet where address = :address")
                        .param("address", wallet.address())
                        .query(Long.class)
                        .single())
                .as("the account is never deleted")
                .isEqualTo(1);
    }

    /** A guest cookie sent to the administrator refresh renews nothing. */
    @Test
    void aGuestCookieNeverRenewsAnAdministratorSession() throws Exception {
        TestWallet guest = TestWallet.generate();
        TestAccounts.betaUser(jdbc, guest.address());
        MockHttpServletResponse guestLogin = new AuthFlow(mvc, json).login(guest);

        assertThat(admin.refresh(AuthFlow.refreshToken(guestLogin)).getStatus()).isEqualTo(401);
        assertThat(admin.refresh(null).getStatus()).isEqualTo(401);
    }

    /** Q4: 5 per minute per IP; the 6th is 429 with Retry-After and a LOGIN_REJECTED RATE_LIMITED. */
    @Test
    void theSixthRequestOfAnIpInAMinuteIsRateLimited() throws Exception {
        String ip = "10.8.0.1";
        for (int i = 0; i < 5; i++) {
            assertThat(admin.challengeResponse(TestWallet.generate().address(), ip)
                            .getStatus())
                    .isEqualTo(200);
        }
        TestWallet sixth = TestWallet.generate();

        MockHttpServletResponse limited = admin.challengeResponse(sixth.address(), ip);

        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).matches("\\d+");
        assertThat(OutboxEvents.reasonsForWallet(jdbc, "LOGIN_REJECTED", sixth.address()))
                .containsExactly("RATE_LIMITED");
    }

    /** Q4 / R-05: the per-address limit counts with the IP, so another IP still reaches the administrator. */
    @Test
    void thePerAddressLimitNeverLocksTheAdministratorOutFromAnotherIp() throws Exception {
        TestWallet wallet = listedWallet();
        for (int i = 0; i < 5; i++) {
            admin.challengeResponse(wallet.address(), "10.9.0.1");
        }
        assertThat(admin.challengeResponse(wallet.address(), "10.9.0.1").getStatus())
                .isEqualTo(429);

        assertThat(admin.challengeResponse(wallet.address(), "10.9.0.2").getStatus())
                .isEqualTo(200);
    }

    /** BR1.2, team.md: the login signature is no credential and an administrator token is no guest credential. */
    @Test
    void theLoginSignatureAuthorisesNothingElse() throws Exception {
        TestWallet wallet = listedWallet();
        String message = admin.challenge(wallet).get("message").asText();
        String signature = wallet.sign(message);
        MockHttpServletResponse login = admin.verify(wallet, message, signature);

        assertThat(mvc.perform(get("/api/v1/admin/kill-switch").header("Authorization", "Bearer " + signature))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
        assertThat(mvc.perform(get("/api/v1/bots").header("Authorization", "Bearer " + admin.accessToken(login)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
    }

    /** BR1.6: logout revokes the session, clears the cookie and writes ADMIN_LOGOUT once. */
    @Test
    void logoutEndsTheSessionAndClearsTheCookie() throws Exception {
        TestWallet wallet = listedWallet();
        MockHttpServletResponse login = admin.login(wallet);
        String token = admin.accessToken(login);
        int before = OutboxEvents.payloads(jdbc, "ADMIN_LOGOUT").size();

        MockHttpServletResponse out = admin.logout(token);

        assertThat(out.getStatus()).isEqualTo(204);
        assertThat(out.getHeader("Set-Cookie")).startsWith("mt_admin_refresh=;").contains("Max-Age=0");
        assertThat(OutboxEvents.payloads(jdbc, "ADMIN_LOGOUT")).hasSize(before + 1);
        assertThat(admin.logout(token).getStatus()).isEqualTo(401);
        assertThat(admin.refresh(AdminAuthFlow.refreshToken(login)).getStatus()).isEqualTo(401);
    }

    /** R-02: logout is NOT public; an encoded spelling of the route still needs a session. */
    @Test
    void logoutAndEncodedSpellingsStillNeedASession() throws Exception {
        assertThat(mvc.perform(post("/api/v1/admin/auth/logout")
                                .header("Idempotency-Key", UUID.randomUUID().toString()))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
        assertThat(mvc.perform(get("/api/v1/%61dmin/kill-switch"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
    }

    private TestWallet listedWallet() {
        TestWallet wallet = TestWallet.generate();
        adminWallets.add(wallet.address());
        return wallet;
    }

    private long sessionsOf(TestWallet wallet) {
        return jdbc.sql("""
                        select count(*) from user_session s join wallet w on w.user_id = s.user_id
                        where w.address = :address
                        """)
                .param("address", wallet.address())
                .query(Long.class)
                .single();
    }
}
