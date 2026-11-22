package com.memetrade.bots.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.testsupport.AbstractPaperRealIT;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 261121-paper-real-unico U1, Step 10 (C2, BR2.1, BR2.2, BR2.5, BR8.1, BR8.3, NFR3.3): the REAL eligibility of the
 * screen over HTTP. Each reason with its shape ({@code effectiveAt} only in QUARANTINE, {@code action} only with the
 * inactive wallet), the eligible answer, 503 when a source fails, the person of the token only, 401 and 403. The read
 * writes nothing. REAL_DISABLED is covered by {@code BotRoutesIT} (the REAL is off in its context).
 */
class RealEligibilityRouteIT extends AbstractPaperRealIT {

    private static final String ROUTE = "/api/v1/bots/real-eligibility";

    @Test
    void anEligiblePersonReadsAvailableWithoutReason() throws Exception {
        JsonNode eligibility = ok(eligiblePerson());

        assertThat(eligibility.get("available").asBoolean()).isTrue();
        assertThat(eligibility.has("reason")).isFalse();
        assertThat(eligibility.has("effectiveAt")).isFalse();
        assertThat(eligibility.has("action")).isFalse();
    }

    @Test
    void theKillSwitchIsTheStrongestReason() throws Exception {
        Person person = signIn(true, false);
        killSwitch(true);

        JsonNode eligibility = ok(person);

        assertThat(eligibility.get("available").asBoolean()).isFalse();
        assertThat(eligibility.get("reason").asText()).isEqualTo("KILL_SWITCH");
    }

    @Test
    void anInactiveWalletCarriesTheActionToActivateIt() throws Exception {
        JsonNode eligibility = ok(signIn(true, true));

        assertThat(eligibility.get("reason").asText()).isEqualTo("WALLET_INACTIVE");
        assertThat(eligibility.get("action").asText()).isEqualTo("ACTIVATE_BOT_WALLET");
        assertThat(eligibility.has("effectiveAt")).isFalse();
    }

    @Test
    void withoutAReleaseTheReasonIsNotReleased() throws Exception {
        Person person = signIn(true, false);
        activeWallet(person, 5 * SOL);

        assertThat(ok(person).get("reason").asText()).isEqualTo("NOT_RELEASED");
    }

    @Test
    void aQuarantineCarriesTheUtcInstantTheReleaseCounts() throws Exception {
        Person person = signIn(true, false);
        activeWallet(person, 5 * SOL);
        Instant effective = quarantinedRelease(person.userId());

        JsonNode eligibility = ok(person);

        assertThat(eligibility.get("reason").asText()).isEqualTo("QUARANTINE");
        assertThat(Instant.parse(eligibility.get("effectiveAt").asText())).isEqualTo(effective);
        assertThat(eligibility.get("effectiveAt").asText()).endsWith("Z");
        assertThat(eligibility.has("action")).isFalse();
    }

    @Test
    void aSourceThatFailsIs503AndAReasonDecidedBeforeItStillAnswers() throws Exception {
        Person person = eligiblePerson();
        faultyState.fault.set(Fault.FAIL);

        MockHttpServletResponse unavailable = read(person, get(ROUTE));
        killSwitch(true);
        MockHttpServletResponse decided = read(person, get(ROUTE));

        assertThat(unavailable.getStatus()).isEqualTo(503);
        assertThat(body(unavailable).get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(decided.getStatus()).isEqualTo(200);
        assertThat(body(decided).get("reason").asText()).isEqualTo("KILL_SWITCH");
    }

    @Test
    void theEligibilityIsAlwaysThatOfThePersonOfTheToken() throws Exception {
        Person eligible = eligiblePerson();
        Person other = signIn(true, true);

        JsonNode mine = ok(eligible);
        JsonNode theirs = body(read(other, get(ROUTE + "?tenantId=" + eligible.tenantId())));

        assertThat(mine.get("available").asBoolean()).isTrue();
        assertThat(theirs.get("reason").asText()).isEqualTo("WALLET_INACTIVE");
    }

    @Test
    void withoutASessionIs401AndWithNewTermsPendingIs403() throws Exception {
        assertThat(mvc.perform(get(ROUTE)).andReturn().getResponse().getStatus())
                .isEqualTo(401);
        MockHttpServletResponse locked = read(staleTermsPerson(), get(ROUTE));
        assertThat(locked.getStatus()).isEqualTo(403);
        assertThat(body(locked).get("code").asText()).isEqualTo("TERMS_REACCEPTANCE_REQUIRED");
    }

    @Test
    void theReadWritesNothing() throws Exception {
        Person person = eligiblePerson();
        long before = writes();

        for (int i = 0; i < 3; i++) {
            ok(person);
        }

        assertThat(writes()).isEqualTo(before);
    }

    private JsonNode ok(Person person) throws Exception {
        MockHttpServletResponse response = read(person, get(ROUTE));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return body(response);
    }

    private long writes() {
        return jdbc.sql("select (select count(*) from bot) + (select count(*) from real_release)"
                        + " + (select count(*) from bot_wallet_account) + (select count(*) from idempotency_record)")
                .query(Long.class)
                .single();
    }
}
