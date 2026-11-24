package com.memetrade.bots.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.pnl.PnlViewsRows;
import com.memetrade.testsupport.AbstractPaperRealIT;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U3, Etapa 6 (BR1.3, BR1.5, BR8.1, NFR3.1, NFR3.5): the counts of {@code BotPage} are of the
 * person of the token only: they do not change when somebody of another tenant creates bots, a tenantId in the query is
 * refused, and {@code matchingCount <= totalCount} in every filter.
 */
@Tag("u3-pnl-views")
class PnlBotCountsTenantIT extends AbstractPaperRealIT {

    @Test
    void theCountsOfAPersonDoNotChangeWhenAnotherTenantCreatesBots() throws Exception {
        Person first = signIn(false, false);
        Person second = signIn(false, false);
        PnlViewsRows rows = new PnlViewsRows(jdbc);
        rows.paperBot(first.tenantId(), first.userId(), "S", PnlViewsRows.STRATEGY);
        rows.realBot(first.tenantId(), first.userId(), "R", PnlViewsRows.STRATEGY);
        JsonNode before = body(read(first, get("/api/v1/bots?market=REAL")));

        for (int i = 0; i < 3; i++) {
            rows.realBot(second.tenantId(), second.userId(), "B" + i, PnlViewsRows.STRATEGY);
        }
        JsonNode after = body(read(first, get("/api/v1/bots?market=REAL")));

        assertThat(after.get("matchingCount")).isEqualTo(before.get("matchingCount"));
        assertThat(after.get("totalCount").asLong()).isEqualTo(2);
        assertThat(body(read(second, get("/api/v1/bots"))).get("totalCount").asLong())
                .isEqualTo(3);
        assertThat(read(first, get("/api/v1/bots?tenantId=" + second.tenantId()))
                        .getStatus())
                .isEqualTo(400);
    }

    @Test
    void theMatchingCountIsNeverAboveTheTotal() throws Exception {
        Person person = signIn(false, false);
        PnlViewsRows rows = new PnlViewsRows(jdbc);
        rows.paperBot(person.tenantId(), person.userId(), "S", PnlViewsRows.STRATEGY);
        rows.realBot(person.tenantId(), person.userId(), "R", PnlViewsRows.STRATEGY);

        for (String query :
                List.of("", "?market=PAPER", "?market=REAL", "?state=PAUSED", "?market=REAL&state=ACTIVE")) {
            JsonNode page = body(read(person, get("/api/v1/bots" + query)));
            assertThat(page.get("matchingCount").asLong())
                    .as(query)
                    .isLessThanOrEqualTo(page.get("totalCount").asLong());
        }
    }
}
