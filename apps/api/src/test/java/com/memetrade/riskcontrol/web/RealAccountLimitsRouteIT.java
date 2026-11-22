package com.memetrade.riskcontrol.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.testsupport.AbstractPaperRealIT;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 261121-paper-real-unico U1, Step 9 (C3, BR3.1 to BR3.3, BR8.1, BR8.3, NFR3.3): the REAL limits of the account over
 * HTTP. 401 without a session, 403 with new terms pending, 200 with the four limits of the person of the token, 409
 * WALLET_INACTIVE without a bot wallet, 503 when a read fails; the read writes nothing and carries no address.
 */
class RealAccountLimitsRouteIT extends AbstractPaperRealIT {

    private static final String ROUTE = "/api/v1/real/account-limits";

    @Test
    void withoutASessionTheRouteIs401() throws Exception {
        assertThat(mvc.perform(get(ROUTE)).andReturn().getResponse().getStatus())
                .isEqualTo(401);
    }

    @Test
    void withNewTermsPendingTheRouteIs403() throws Exception {
        MockHttpServletResponse locked = read(staleTermsPerson(), get(ROUTE));

        assertThat(locked.getStatus()).isEqualTo(403);
        assertThat(body(locked).get("code").asText()).isEqualTo("TERMS_REACCEPTANCE_REQUIRED");
    }

    @Test
    void theFourLimitsOfThePersonOfTheTokenAreReadInLamportsAndPercent() throws Exception {
        Person person = signIn(true, true);
        activeWallet(person, 5 * SOL);
        long rows = rowsOfTheBarrier();

        MockHttpServletResponse response = read(person, get(ROUTE));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        JsonNode limits = body(response);
        assertThat(limits.get("currency").asText()).isEqualTo("SOL");
        assertThat(limits.get("cap").get("status").asText()).isEqualTo("KNOWN");
        assertThat(limits.get("cap").get("value").asText()).isEqualTo("5000000000");
        assertThat(limits.get("orderMax").get("value").asText()).isEqualTo("500000000");
        assertThat(limits.get("orderMax").get("percentOfCap").asText()).isEqualTo("10");
        assertThat(limits.get("dailyBuys").get("count").asInt()).isEqualTo(20);
        assertThat(limits.get("dailyLoss").get("value").asText()).isEqualTo("1000000000");
        assertThat(response.getContentAsString()).doesNotContain(person.wallet().address());
        assertThat(rowsOfTheBarrier()).isEqualTo(rows);
    }

    @Test
    void aCapOfZeroIsNotDefinedNeverZero() throws Exception {
        Person person = signIn(true, true);
        activeWallet(person, 0);

        JsonNode limits = body(read(person, get(ROUTE)));

        assertThat(limits.get("cap").get("status").asText()).isEqualTo("UNKNOWN");
        assertThat(limits.get("cap").get("reason").asText()).isEqualTo("NOT_DEFINED");
        assertThat(limits.get("orderMax").get("reason").asText()).isEqualTo("NOT_DEFINED");
        assertThat(limits.get("dailyLoss").get("reason").asText()).isEqualTo("NOT_DEFINED");
        assertThat(limits.get("dailyBuys").get("status").asText()).isEqualTo("KNOWN");
    }

    @Test
    void withoutABotWalletTheAnswerIs409WalletInactive() throws Exception {
        MockHttpServletResponse refused = read(signIn(true, true), get(ROUTE));

        assertThat(refused.getStatus()).isEqualTo(409);
        assertThat(body(refused).get("code").asText()).isEqualTo("REAL_NOT_ELIGIBLE");
        assertThat(body(refused).get("reason").asText()).isEqualTo("WALLET_INACTIVE");
    }

    @Test
    void aFailedReadIs503WithTheCorrelationId() throws Exception {
        Person person = signIn(true, true);
        activeWallet(person, 5 * SOL);
        faultyState.fault.set(Fault.FAIL);

        MockHttpServletResponse unavailable = read(person, get(ROUTE));

        assertThat(unavailable.getStatus()).isEqualTo(503);
        assertThat(body(unavailable).get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(body(unavailable).get("correlationId").asText()).isNotBlank();
    }

    @Test
    void aTenantInTheQueryIsIgnoredAndTheLimitsAreAlwaysThoseOfTheToken() throws Exception {
        Person owner = signIn(true, true);
        activeWallet(owner, 2 * SOL);
        Person other = signIn(true, true);
        activeWallet(other, 7 * SOL);

        JsonNode limits = body(read(other, get(ROUTE + "?tenantId=" + owner.tenantId())));

        assertThat(limits.get("cap").get("value").asText()).isEqualTo("7000000000");
    }

    private long rowsOfTheBarrier() {
        return jdbc.sql("select (select count(*) from real_risk_evaluation) + (select count(*) from real_risk_day)")
                .query(Long.class)
                .single();
    }
}
