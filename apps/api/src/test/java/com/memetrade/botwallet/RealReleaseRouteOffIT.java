package com.memetrade.botwallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 261113-trading-real-bot U6, Step 3, written before the code (rules.md "Mudança de contrato" item 1, BR1.1; team.md
 * "REAL desligado por padrão"): with the shipped configuration the read of the release answers
 * {@code 403 REAL_DISABLED}, even for a released person; the screen then hides the REAL area.
 */
class RealReleaseRouteOffIT extends AbstractRealIT {

    @Test
    void withTheRealSwitchedOffTheReadAnswersRealDisabled() throws Exception {
        Person person = signIn(true, true);

        MockHttpServletResponse response = call(person, get("/api/v1/real/release"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(body(response).get("code").asText()).isEqualTo("REAL_DISABLED");
        assertThat(body(response).get("correlationId").asText()).isNotBlank();
    }
}
