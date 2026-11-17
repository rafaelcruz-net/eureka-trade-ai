package com.memetrade.botwallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.botwallet.application.CapService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * 261113-trading-real-bot U6, Step 3, written before the code (rules.md "Mudança de contrato" items 1 and 2;
 * U6-NFR1.6, U6-NFR5.2; team.md mandatory tests "isolamento entre contas" and "novo aceite dos termos"): the read of
 * the release {@code GET /api/v1/real/release} in each state, only for the person of the token, behind the
 * reacceptance of the terms; and the two read-only fields of the bot wallet view, {@code release} and
 * {@code capSetBy}. The REAL switched off is in {@link RealReleaseRouteOffIT}.
 */
@TestPropertySource(properties = "memetrade.real.enabled=true")
// U2 (BR1.6): REAL on with the simulated market sources is allowed only in the test and local profiles.
@ActiveProfiles("local")
class RealReleaseRouteIT extends AbstractRealIT {

    private static final String ROUTE = "/api/v1/real/release";

    @Autowired
    private CapService caps;

    private void insertRelease(UUID userId, String state, String blockedReason, boolean revoked) {
        Instant requested = clock.instant().minus(Duration.ofHours(2));
        jdbc.sql("""
                        insert into real_release (release_id, user_id, network, requested_by, requested_at, effective_at,
                          revoked_at, state, blocked_reason)
                        values (:id, :user, 'LOCALNET', :user, :requested, :effective, :revoked, :state, :reason)""")
                .param("id", UUID.randomUUID())
                .param("user", userId)
                .param("requested", Timestamp.from(requested))
                .param("effective", Timestamp.from(requested.plus(Duration.ofHours(24))))
                .param("revoked", revoked ? Timestamp.from(requested.plus(Duration.ofMinutes(5))) : null)
                .param("state", state)
                .param("reason", blockedReason)
                .update();
    }

    private JsonNode release(Person person) throws Exception {
        MockHttpServletResponse response = call(person, get(ROUTE));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        return body(response);
    }

    private JsonNode activeWallet(Person person) throws Exception {
        MockHttpServletResponse challenge = call(
                person,
                post("/api/v1/real/consents/challenge")
                        .content(json.writeValueAsString(Map.of("kind", "ACTIVATE", "capLamports", "2000000000"))));
        assertThat(challenge.getStatus()).as(challenge.getContentAsString()).isEqualTo(201);
        JsonNode issued = body(challenge);
        MockHttpServletResponse activation = call(
                person,
                post("/api/v1/real/bot-wallet")
                        .content(json.writeValueAsString(Map.of(
                                "challengeId",
                                issued.get("challengeId").asText(),
                                "signature",
                                person.wallet().sign(issued.get("message").asText())))));
        assertThat(activation.getStatus()).as(activation.getContentAsString()).isEqualTo(201);
        return body(activation);
    }

    @Test
    void anActiveReleaseShowsTheNetworkTheStateAndWhenItTookEffect() throws Exception {
        Person person = signIn(true, true);

        JsonNode answer = release(person);

        assertThat(answer.get("network").asText()).isEqualTo("LOCALNET");
        assertThat(answer.get("release").get("state").asText()).isEqualTo("ACTIVE");
        assertThat(answer.get("release").get("effectiveAt").asText()).isNotBlank();
        assertThat(answer.get("release").has("blockedReason")).isFalse();
    }

    @Test
    void withoutAnyReleaseTheStateIsNone() throws Exception {
        Person person = signIn(true, false);

        JsonNode answer = release(person);

        assertThat(answer.get("network").asText()).isEqualTo("LOCALNET");
        assertThat(answer.get("release").get("state").asText()).isEqualTo("NONE");
        assertThat(answer.get("release").has("effectiveAt")).isFalse();
    }

    @Test
    void aReleaseInQuarantineShowsWhenItEnds() throws Exception {
        Person person = signIn(true, false);
        insertRelease(person.userId(), "QUARANTINE", null, false);

        JsonNode answer = release(person).get("release");

        assertThat(answer.get("state").asText()).isEqualTo("QUARANTINE");
        assertThat(Instant.parse(answer.get("effectiveAt").asText()))
                .isEqualTo(clock.instant().plus(Duration.ofHours(22)));
    }

    @Test
    void aBlockedReleaseShowsItsReasonAndARevokedOneShowsRevoked() throws Exception {
        Person blocked = signIn(true, false);
        insertRelease(blocked.userId(), "BLOCKED", "KILL_SWITCH_ON", false);
        Person revoked = signIn(true, false);
        insertRelease(revoked.userId(), "REVOKED", null, true);

        JsonNode blockedAnswer = release(blocked).get("release");
        JsonNode revokedAnswer = release(revoked).get("release");

        assertThat(blockedAnswer.get("state").asText()).isEqualTo("BLOCKED");
        assertThat(blockedAnswer.get("blockedReason").asText()).isEqualTo("KILL_SWITCH_ON");
        assertThat(revokedAnswer.get("state").asText()).isEqualTo("REVOKED");
    }

    @Test
    void anotherAccountNeverSeesTheReleaseOfSomeoneElse() throws Exception {
        Person released = signIn(true, true);
        Person other = signIn(true, false);

        JsonNode mine = release(released);
        JsonNode theirs = release(other);

        assertThat(mine.get("release").get("state").asText()).isEqualTo("ACTIVE");
        assertThat(theirs.get("release").get("state").asText()).isEqualTo("NONE");
        assertThat(theirs.toString()).doesNotContain(released.userId().toString());
    }

    @Test
    void withoutTheAcceptanceOfTheCurrentTermsTheReadIsLocked() throws Exception {
        // A person with no acceptance of the current version of the terms: the reacceptance lock, not an exception.
        com.memetrade.testsupport.TestWallet wallet = com.memetrade.testsupport.TestWallet.generate();
        com.memetrade.testsupport.TestAccounts.Seeded seeded =
                com.memetrade.testsupport.TestAccounts.betaUserWithoutAcceptance(jdbc, wallet.address());
        release(seeded.userId());
        com.memetrade.testsupport.AuthFlow auth = new com.memetrade.testsupport.AuthFlow(mvc, json);
        Person person = new Person(
                "Bearer " + auth.accessToken(auth.login(wallet)), seeded.tenantId(), seeded.userId(), wallet);

        MockHttpServletResponse response = call(person, get(ROUTE));
        MockHttpServletResponse wallets = call(person, get("/api/v1/real/bot-wallet"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(body(response).get("code").asText()).isEqualTo("TERMS_REACCEPTANCE_REQUIRED");
        assertThat(body(wallets).get("code").asText())
                .as("the read of the bot wallet stays an exception of the lock")
                .isNotEqualTo("TERMS_REACCEPTANCE_REQUIRED");
    }

    @Test
    void withoutATokenTheReadIsUnauthorized() throws Exception {
        MockHttpServletResponse response = mvc.perform(get(ROUTE)).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void theBotWalletViewCarriesTheReleaseAndACapSetByThePerson() throws Exception {
        Person person = signIn(true, true);
        JsonNode activated = activeWallet(person);

        JsonNode view = body(call(person, get("/api/v1/real/bot-wallet")));

        assertThat(activated.get("capSetBy").asText()).isEqualTo("USER");
        assertThat(activated.get("release").get("state").asText()).isEqualTo("ACTIVE");
        assertThat(view.get("capSetBy").asText()).isEqualTo("USER");
        assertThat(view.get("release").get("state").asText()).isEqualTo("ACTIVE");
        assertThat(view.get("release").get("effectiveAt").asText()).isNotBlank();
    }

    @Test
    void aCapLoweredByTheAdministratorShowsAdminUntilThePersonChangesIt() throws Exception {
        Person person = signIn(true, true);
        activeWallet(person);

        transactions.executeWithoutResult(status -> caps.lower(UUID.randomUUID(), person.userId(), 500_000_000L));
        JsonNode lowered = body(call(person, get("/api/v1/real/bot-wallet")));
        MockHttpServletResponse changed = call(
                person,
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                                "/api/v1/real/bot-wallet/preferences")
                        .content("{\"capLamports\":\"400000000\"}"));

        assertThat(lowered.get("capLamports").asText()).isEqualTo("500000000");
        assertThat(lowered.get("capSetBy").asText()).isEqualTo("ADMIN");
        assertThat(changed.getStatus()).as(changed.getContentAsString()).isEqualTo(200);
        assertThat(body(changed).get("capSetBy").asText()).isEqualTo("USER");
    }
}
