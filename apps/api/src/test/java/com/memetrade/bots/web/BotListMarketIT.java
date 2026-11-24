package com.memetrade.bots.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.pnl.PnlViewsRows;
import com.memetrade.testsupport.AbstractPaperRealIT;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 261121-paper-real-unico U3, Etapa 6 (test after the layer; BR1.2, BR1.3, BR1.5, BR6.3, AC2.3.2, AC2.3.3, NFR1.2,
 * NFR3.6): {@code listBots} with {@code market}: only the bots of that mode, combined with the state; the counts of the
 * page ({@code matchingCount}) and of the person ({@code totalCount}); the same order of the API in every filter; an
 * unknown market is 400; the reads write nothing.
 */
@Tag("u3-pnl-views")
class BotListMarketIT extends AbstractPaperRealIT {

    private record Story(Person person, List<UUID> paper, List<UUID> real) {}

    private Story story() throws Exception {
        Person person = signIn(false, false);
        PnlViewsRows rows = new PnlViewsRows(jdbc);
        List<UUID> paper = new ArrayList<>();
        List<UUID> real = new ArrayList<>();
        paper.add(rows.paperBot(person.tenantId(), person.userId(), "S1", PnlViewsRows.STRATEGY));
        real.add(rows.realBot(person.tenantId(), person.userId(), "R1", PnlViewsRows.STRATEGY));
        paper.add(rows.paperBot(person.tenantId(), person.userId(), "S2", PnlViewsRows.OTHER_STRATEGY));
        real.add(rows.realBot(person.tenantId(), person.userId(), "R2", PnlViewsRows.STRATEGY));
        real.add(rows.realBot(person.tenantId(), person.userId(), "R3", PnlViewsRows.OTHER_STRATEGY));
        return new Story(person, paper, real);
    }

    @Test
    void marketPaperListsOnlyThePaperBotsWithTheCounts() throws Exception {
        Story story = story();

        JsonNode page = ok(story.person(), "/api/v1/bots?market=PAPER");

        assertThat(ids(page)).containsExactlyInAnyOrderElementsOf(story.paper());
        page.get("items").forEach(bot -> assertThat(bot.get("market").asText()).isEqualTo("PAPER"));
        assertThat(page.get("matchingCount").asLong()).isEqualTo(2);
        assertThat(page.get("totalCount").asLong()).isEqualTo(5);
    }

    @Test
    void marketRealListsOnlyTheRealBotsAndCombinesWithTheState() throws Exception {
        Story story = story();

        JsonNode real = ok(story.person(), "/api/v1/bots?market=REAL");
        JsonNode activeReal = ok(story.person(), "/api/v1/bots?market=REAL&state=ACTIVE");
        JsonNode pausedReal = ok(story.person(), "/api/v1/bots?market=REAL&state=PAUSED");

        assertThat(ids(real)).containsExactlyInAnyOrderElementsOf(story.real());
        assertThat(real.get("matchingCount").asLong()).isEqualTo(3);
        assertThat(ids(activeReal)).containsExactlyInAnyOrderElementsOf(story.real());
        assertThat(activeReal.get("matchingCount").asLong()).isEqualTo(3);
        assertThat(pausedReal.get("items")).isEmpty();
        assertThat(pausedReal.get("matchingCount").asLong()).isZero();
        assertThat(pausedReal.get("totalCount").asLong()).isEqualTo(5);
    }

    @Test
    void theOrderOfTheApiIsTheSameInEveryFilterAndNeverMixesByValue() throws Exception {
        Story story = story();

        List<UUID> all = ids(ok(story.person(), "/api/v1/bots"));
        List<UUID> paper = ids(ok(story.person(), "/api/v1/bots?market=PAPER"));
        List<UUID> real = ids(ok(story.person(), "/api/v1/bots?market=REAL"));

        assertThat(all.stream().filter(story.paper()::contains).toList()).isEqualTo(paper);
        assertThat(all.stream().filter(story.real()::contains).toList()).isEqualTo(real);
        assertThat(ok(story.person(), "/api/v1/bots").get("matchingCount").asLong())
                .isEqualTo(5);
    }

    @Test
    void anUnknownMarketIs400() throws Exception {
        Story story = story();

        for (String market : List.of("OTHER", "real", "")) {
            MockHttpServletResponse response = read(story.person(), get("/api/v1/bots?market=" + market));
            if (market.isEmpty()) {
                assertThat(response.getStatus()).isEqualTo(200);
            } else {
                assertThat(response.getStatus()).as(market).isEqualTo(400);
                assertThat(body(response).get("code").asText()).isEqualTo("VALIDATION_FAILED");
            }
        }
    }

    @Test
    void theReadsWriteNothing() throws Exception {
        Story story = story();
        PnlViewsRows rows = new PnlViewsRows(jdbc);
        long before = rows.rowsOfTheReads();

        ok(story.person(), "/api/v1/bots?market=PAPER");
        ok(story.person(), "/api/v1/bots?market=REAL");
        ok(story.person(), "/api/v1/bots");

        assertThat(rows.rowsOfTheReads()).isEqualTo(before);
    }

    private JsonNode ok(Person person, String route) throws Exception {
        MockHttpServletResponse response = read(person, get(route));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return body(response);
    }

    static List<UUID> ids(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.get("items")
                .forEach(bot -> ids.add(UUID.fromString(bot.get("botId").asText())));
        return ids;
    }
}
