package com.memetrade.bots.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.bots.api.OwnQuantitySell;
import com.memetrade.pnl.PnlViewsRows;
import com.memetrade.testsupport.AbstractPaperRealIT;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 261121-paper-real-unico U4, Etapa 19 (after the layer; C7, BR5.3, AC6.2.1, AC6.2.2, NFR6.2): {@code listBots} always
 * sends {@code realOwnQuantitySell}, the value of the flag in the api, {@code false} by default, in every filter and
 * page; it depends neither on the market, the state, the cursor nor the person. The api registers none of the meters of
 * the own quantity (they live in the worker).
 */
@Tag("u4-own-quantity-cycle")
class BotListOwnQuantityFieldIT extends AbstractPaperRealIT {

    @Autowired
    private OwnQuantitySell flag;

    @Autowired
    private MeterRegistry meters;

    @Test
    void theFlagIsAlwaysSentAndFalseByDefaultWhateverTheFilter() throws Exception {
        Person person = signIn(false, false);
        PnlViewsRows rows = new PnlViewsRows(jdbc);
        rows.paperBot(person.tenantId(), person.userId(), "S1", PnlViewsRows.STRATEGY);
        rows.realBot(person.tenantId(), person.userId(), "R1", PnlViewsRows.STRATEGY);
        Person empty = signIn(false, false);

        assertThat(flag.enabled()).isFalse();
        for (String route : List.of(
                "/api/v1/bots",
                "/api/v1/bots?market=REAL",
                "/api/v1/bots?market=PAPER",
                "/api/v1/bots?state=PAUSED",
                "/api/v1/bots?limit=1")) {
            JsonNode page = ok(person, route);
            assertThat(page.has("realOwnQuantitySell")).as(route).isTrue();
            assertThat(page.get("realOwnQuantitySell").isBoolean()).as(route).isTrue();
            assertThat(page.get("realOwnQuantitySell").asBoolean()).as(route).isFalse();
        }
        assertThat(ok(empty, "/api/v1/bots").get("realOwnQuantitySell").asBoolean())
                .isFalse();
    }

    @Test
    void theApiHasNoneOfTheMetersOfTheOwnQuantity() {
        assertThat(meters.find("real_own_holdings_reads").meters()).isEmpty();
        assertThat(meters.find("real_own_holdings_unavailable").meters()).isEmpty();
        assertThat(meters.find("real_cycle_sell_capped").meters()).isEmpty();
        assertThat(meters.find("real_own_holdings_stuck_bots").meters()).isEmpty();
    }

    private JsonNode ok(Person person, String route) throws Exception {
        MockHttpServletResponse response = read(person, get(route));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return body(response);
    }
}
