package com.memetrade.realexecution.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.realexecution.AbstractRealExecutionIT;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.TestAccounts;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 261113-trading-real-bot U6, Step 3, written before the code (rules.md "Mudança de contrato" item 3, BR3.2,
 * U6-NFR5.2): the signing session carries {@code serverTime}, the UTC instant of the injected clock of the server when
 * it built the answer, so the screen can discount the difference of clocks (the {@code Date} header is not exposed by
 * the CORS). The deadline is unchanged and still decided by the server.
 */
@Tag("u6-real-modes-ui")
class SigningSessionServerTimeIT extends AbstractRealExecutionIT {

    private String bearer(Setup setup) throws Exception {
        TestAccounts.acceptTerms(jdbc, setup.tenantId(), setup.userId(), "v3");
        AuthFlow auth = new AuthFlow(mvc, json);
        return "Bearer " + auth.accessToken(auth.login(setup.main()));
    }

    private UUID openSession(Setup setup) {
        UUID request = pendingBuy(setup, 50_000_000L);
        consumer.runOnce();
        driver.runOnce();
        UUID order = orderOf(request);
        assertThat(state(order)).isEqualTo("QUOTED");
        return order;
    }

    private JsonNode session(String bearer, UUID order) throws Exception {
        MockHttpServletResponse response = mvc.perform(
                        get("/api/v1/real/orders/" + order + "/signing-session").header("Authorization", bearer))
                .andReturn()
                .getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return json.readTree(response.getContentAsString());
    }

    @Test
    void theSessionCarriesTheInstantOfTheServerClock() throws Exception {
        Setup setup = realBot("SIGNED");
        UUID order = openSession(setup);
        String bearer = bearer(setup);

        JsonNode first = session(bearer, order);

        assertThat(Instant.parse(first.get("serverTime").asText())).isEqualTo(clock.instant());
        assertThat(Instant.parse(first.get("deadline").asText())).isAfter(clock.instant());
    }

    @Test
    void theServerTimeFollowsTheClockAndTheDeadlineDoesNotMove() throws Exception {
        Setup setup = realBot("SIGNED");
        UUID order = openSession(setup);
        String bearer = bearer(setup);
        JsonNode before = session(bearer, order);

        clock.advance(Duration.ofSeconds(3));
        JsonNode after = session(bearer, order);

        assertThat(Instant.parse(after.get("serverTime").asText()))
                .isEqualTo(Instant.parse(before.get("serverTime").asText()).plusSeconds(3));
        assertThat(after.get("deadline").asText())
                .isEqualTo(before.get("deadline").asText());
    }
}
