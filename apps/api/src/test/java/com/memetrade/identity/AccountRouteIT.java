package com.memetrade.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.shared.wallet.WalletAddress;
import com.memetrade.testsupport.AbstractU3IT;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestInvites;
import com.memetrade.testsupport.TestWallet;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Passo 12 (F8; BR5.1 to BR5.4; NFR5.1, NFR6.12, NFR7.1): the Account screen of the API shows only the caller's own
 * account, the masked wallet, the provider, the absolute end of the current session, the accepted terms and REAL mode
 * always unavailable; without a session, 401.
 */
class AccountRouteIT extends AbstractU3IT {

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
    void theAccountShowsTheMaskedWalletTheProviderTheAbsoluteSessionEndAndTheAcceptedTerms() throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestInvites.Created invite = TestInvites.pending(jdbc).insert();
        MockHttpServletResponse created = auth.createAccount(wallet, invite.code());
        JsonNode session = json.readTree(created.getContentAsString());

        JsonNode account = account(auth.accessToken(created));

        assertThat(account.get("walletAddressMasked").asText()).isEqualTo(WalletAddress.mask(wallet.address()));
        assertThat(account.get("provider").asText()).isEqualTo("SOLFLARE");
        assertThat(account.get("sessionExpiresAt").asText())
                .isEqualTo(session.get("sessionExpiresAt").asText())
                .isEqualTo(clock.instant().plus(Duration.ofHours(12)).toString());
        assertThat(account.get("acceptedTerms").get("version").asText()).isEqualTo("v1");
        assertThat(account.get("acceptedTerms").get("acceptedAt").asText())
                .isEqualTo(clock.instant().toString());
        assertThat(account.get("acceptedTerms").get("termsText").asText()).startsWith("Versão provisória");
        assertThat(account.get("acceptedTerms").get("riskNoticeText").asText()).contains("modo REAL");
        assertThat(account.get("realMode").asText()).isEqualTo("UNAVAILABLE");
    }

    /** Registered negative test of the CLIENT route (BR2.3 of U1, NFR5.1): two people of two tenants. */
    @Test
    void anotherTenantsSessionNeverSeesThisAccount() throws Exception {
        TestWallet alice = TestWallet.generate();
        TestWallet bob = TestWallet.generate();
        TestInvites.Created aliceInvite = TestInvites.pending(jdbc).insert();
        TestInvites.Created bobInvite = TestInvites.pending(jdbc).insert();
        String aliceToken = auth.accessToken(auth.createAccount(alice, aliceInvite.code()));
        clock.advance(Duration.ofMinutes(3));
        String bobToken = auth.accessToken(auth.createAccount(bob, bobInvite.code()));
        assertThat(aliceInvite.tenantId()).isNotEqualTo(bobInvite.tenantId());

        String aliceBody = mvc.perform(get("/api/v1/account").header("Authorization", "Bearer " + aliceToken))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String bobBody = mvc.perform(get("/api/v1/account").header("Authorization", "Bearer " + bobToken))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(aliceBody)
                .contains(WalletAddress.mask(alice.address()))
                .doesNotContain(WalletAddress.mask(bob.address()));
        assertThat(bobBody)
                .contains(WalletAddress.mask(bob.address()))
                .doesNotContain(WalletAddress.mask(alice.address()));
        assertThat(json.readTree(aliceBody)
                        .get("acceptedTerms")
                        .get("acceptedAt")
                        .asText())
                .isNotEqualTo(json.readTree(bobBody)
                        .get("acceptedTerms")
                        .get("acceptedAt")
                        .asText());
        assertThat(aliceBody + bobBody)
                .doesNotContain(aliceInvite.tenantId().toString())
                .doesNotContain(bobInvite.tenantId().toString());
    }

    @Test
    void identificationParametersAreIgnoredTheTokenAloneDecides() throws Exception {
        TestWallet alice = TestWallet.generate();
        TestWallet bob = TestWallet.generate();
        String aliceToken = auth.accessToken(
                auth.createAccount(alice, TestInvites.pending(jdbc).insert().code()));
        TestAccounts.Seeded bobAccount = TestAccounts.betaUser(jdbc, bob.address());

        String body = mvc.perform(
                        get("/api/v1/account?userId=" + bobAccount.userId() + "&tenantId=" + bobAccount.tenantId())
                                .header("Authorization", "Bearer " + aliceToken))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .contains(WalletAddress.mask(alice.address()))
                .doesNotContain(WalletAddress.mask(bob.address()));
    }

    @Test
    void withoutASessionTheAccountIs401() throws Exception {
        assertThat(mvc.perform(get("/api/v1/account")).andReturn().getResponse().getStatus())
                .isEqualTo(401);
        assertThat(mvc.perform(get("/api/v1/account").header("Authorization", "Bearer forged"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
    }

    @Test
    void theFullAddressNeverLeavesAndRealModeIsAlwaysUnavailableWithoutAnyControl() throws Exception {
        TestWallet wallet = TestWallet.generate();
        String token = auth.accessToken(
                auth.createAccount(wallet, TestInvites.pending(jdbc).insert().code()));

        MockHttpServletResponse response = mvc.perform(
                        get("/api/v1/account").header("Authorization", "Bearer " + token))
                .andReturn()
                .getResponse();

        assertThat(response.getContentAsString()).doesNotContain(wallet.address());
        JsonNode account = json.readTree(response.getContentAsString());
        assertThat(account.get("realMode").asText()).isEqualTo("UNAVAILABLE");
        assertThat(account.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder(
                        "walletAddressMasked", "provider", "sessionExpiresAt", "acceptedTerms", "realMode");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    }

    @Test
    void anAccountCreatedWithoutAnInvitationHasNoAcceptedTerms() throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.betaUser(jdbc, wallet.address());

        JsonNode account = account(auth.accessToken(auth.login(wallet)));

        assertThat(account.has("acceptedTerms")).isFalse();
        assertThat(account.get("provider").asText()).isEqualTo("PHANTOM");
        assertThat(account.get("realMode").asText()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void aLaterSessionShowsItsOwnAbsoluteEnd() throws Exception {
        TestWallet wallet = TestWallet.generate();
        auth.createAccount(wallet, TestInvites.pending(jdbc).insert().code());
        clock.advance(Duration.ofHours(2));

        JsonNode account = account(auth.accessToken(auth.login(wallet)));

        assertThat(account.get("sessionExpiresAt").asText())
                .isEqualTo(clock.instant().plus(Duration.ofHours(12)).toString());
    }

    private JsonNode account(String token) throws Exception {
        MockHttpServletResponse response = mvc.perform(
                        get("/api/v1/account").header("Authorization", "Bearer " + token))
                .andReturn()
                .getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return json.readTree(response.getContentAsString());
    }
}
