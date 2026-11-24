package com.memetrade.bots.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.memetrade.testsupport.AbstractPaperRealIT;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;

/**
 * 261121-paper-real-unico U4, Etapa 19 (after the layer; C7, BR5.3, AC6.2.1): with the property on in the api,
 * {@code listBots} sends {@code realOwnQuantitySell: true} in every filter, for a person with bots or without any.
 */
@Tag("u4-own-quantity-cycle")
@TestPropertySource(properties = "memetrade.real.own-quantity-sell=true")
class BotListOwnQuantityFlagOnIT extends AbstractPaperRealIT {

    @Test
    void theFlagOnIsSentTrueInEveryFilter() throws Exception {
        Person person = signIn(false, false);

        for (String route : List.of("/api/v1/bots", "/api/v1/bots?market=PAPER", "/api/v1/bots?market=REAL")) {
            MockHttpServletResponse response = read(person, get(route));
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(body(response).get("realOwnQuantitySell").asBoolean())
                    .as(route)
                    .isTrue();
        }
    }
}
