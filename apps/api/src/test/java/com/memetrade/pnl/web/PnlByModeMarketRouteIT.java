package com.memetrade.pnl.web;

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
 * 261121-paper-real-unico U3, Etapa 5 (test after the layer; BR1.6, BR3.1, BR3.2, BR8.1, AC4.2.6, NFR3.1, NFR3.4,
 * NFR4.2, NFR4.13): C4 with {@code market} answers only the block asked and OMITS the key of the other (never
 * {@code null}); without {@code market} both blocks come. An unknown market, a tenantId or an accountId are 400 and never
 * change the person read. Without a session 401; with new terms pending 403. Three reads write nothing.
 */
@Tag("u3-pnl-views")
class PnlByModeMarketRouteIT extends AbstractPaperRealIT {

    private static final String ROUTE = "/api/v1/pnl/by-mode";

    @Test
    void withoutMarketBothKeysArePresentAndNotNull() throws Exception {
        Person person = seeded();

        JsonNode answer = ok(person, ROUTE);

        assertThat(keys(answer)).containsExactly("period", "paper", "real");
        assertThat(answer.get("paper").isObject()).isTrue();
        assertThat(answer.get("real").isObject()).isTrue();
    }

    @Test
    void withMarketPaperTheKeyRealIsAbsent() throws Exception {
        Person person = seeded();

        MockHttpServletResponse response = read(person, get(ROUTE + "?market=PAPER"));

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode answer = body(response);
        assertThat(keys(answer)).containsExactly("period", "paper");
        assertThat(response.getContentAsString()).doesNotContain("\"real\"");
        assertThat(answer.get("paper").get("currency").asText()).isEqualTo("USD");
        assertThat(answer.get("paper").get("disclaimer").asText()).isNotBlank();
    }

    @Test
    void withMarketRealTheKeyPaperIsAbsentAndThereIsNoDisclaimer() throws Exception {
        Person person = seeded();

        MockHttpServletResponse response = read(person, get(ROUTE + "?market=REAL&period=ALL"));

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode answer = body(response);
        assertThat(keys(answer)).containsExactly("period", "real");
        assertThat(response.getContentAsString()).doesNotContain("\"paper\"").doesNotContain("disclaimer");
        assertThat(answer.get("real").get("currency").asText()).isEqualTo("SOL");
    }

    @Test
    void anUnknownMarketATenantIdOrAnAccountIdIs400() throws Exception {
        Person person = seeded();
        Person other = signIn(false, false);

        for (String query : List.of(
                "?market=OTHER",
                "?market=paper",
                "?tenantId=" + other.tenantId(),
                "?accountId=" + other.userId(),
                "?market=REAL&tenantId=" + other.tenantId())) {
            MockHttpServletResponse response = read(person, get(ROUTE + query));
            assertThat(response.getStatus()).as(query).isEqualTo(400);
            assertThat(body(response).get("code").asText()).isEqualTo("VALIDATION_FAILED");
        }
    }

    @Test
    void withoutASessionIs401AndWithNewTermsPendingIs403() throws Exception {
        assertThat(mvc.perform(get(ROUTE + "?market=REAL"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
        MockHttpServletResponse locked = read(staleTermsPerson(), get(ROUTE + "?market=PAPER"));
        assertThat(locked.getStatus()).isEqualTo(403);
        assertThat(body(locked).get("code").asText()).isEqualTo("TERMS_REACCEPTANCE_REQUIRED");
    }

    @Test
    void threeReadsWithMarketWriteNothing() throws Exception {
        Person person = seeded();
        PnlViewsRows rows = new PnlViewsRows(jdbc);
        long before = rows.rowsOfTheReads();

        ok(person, ROUTE + "?market=PAPER");
        ok(person, ROUTE + "?market=REAL");
        ok(person, ROUTE + "?market=REAL&period=RANGE&from=2026-10-01T00:00:00Z&to=2026-11-01T00:00:00Z");

        assertThat(rows.rowsOfTheReads()).isEqualTo(before);
    }

    private Person seeded() throws Exception {
        Person person = signIn(false, false);
        PnlViewsRows rows = new PnlViewsRows(jdbc);
        rows.paperBot(person.tenantId(), person.userId(), "Simulado", PnlViewsRows.STRATEGY);
        UUID real = rows.realBot(person.tenantId(), person.userId(), "Real", PnlViewsRows.STRATEGY);
        assertThat(real).isNotNull();
        return person;
    }

    private JsonNode ok(Person person, String route) throws Exception {
        MockHttpServletResponse response = read(person, get(route));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return body(response);
    }

    private static List<String> keys(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
