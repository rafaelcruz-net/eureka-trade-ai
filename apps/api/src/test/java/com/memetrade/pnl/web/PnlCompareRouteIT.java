package com.memetrade.pnl.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.pnl.PnlViewsRows;
import com.memetrade.testsupport.AbstractPaperRealIT;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 261121-paper-real-unico U3, Etapa 9 (test after the layer; BR5.1 to BR5.5, BR6.2, BR8.1, AC8.1.1, AC8.1.3, NFR1.4,
 * NFR3.1, NFR3.3, NFR3.4, NFR3.7, NFR4.1, NFR4.9, NFR4.13): the route C6. 200 with the two sides, each in its own
 * currency, the disclaimer only on the PAPER side; 400 for an id or a period; 422 only for two bots of the person with
 * different strategies, without any value of P&L; 401 and 403; no address; three reads write nothing.
 */
@Tag("u3-pnl-views")
class PnlCompareRouteIT extends AbstractPaperRealIT {

    private static final String ROUTE = "/api/v1/pnl/compare";

    private record Pair(Person person, UUID paper, UUID real, UUID otherReal) {}

    private Pair pair() throws Exception {
        Person person = signIn(false, false);
        PnlViewsRows rows = new PnlViewsRows(jdbc);
        UUID paper = rows.paperBot(person.tenantId(), person.userId(), "Simulado", PnlViewsRows.STRATEGY);
        UUID real = rows.realBot(person.tenantId(), person.userId(), "Real", PnlViewsRows.STRATEGY);
        UUID otherReal = rows.realBot(person.tenantId(), person.userId(), "Outro", PnlViewsRows.OTHER_STRATEGY);
        return new Pair(person, paper, real, otherReal);
    }

    private static String compare(UUID paper, UUID real) {
        return ROUTE + "?paperBotId=" + paper + "&realBotId=" + real;
    }

    @Test
    void theTwoSidesComeEachInItsCurrencyWithTheDisclaimerOnlyOnThePaperSide() throws Exception {
        Pair pair = pair();

        MockHttpServletResponse response = read(pair.person(), get(compare(pair.paper(), pair.real())));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        JsonNode answer = body(response);
        assertThat(answer.get("period").get("kind").asText()).isEqualTo("ALL");
        JsonNode paper = answer.get("paper");
        assertThat(paper.get("status").asText()).isEqualTo("AVAILABLE");
        assertThat(paper.get("currency").asText()).isEqualTo("USD");
        assertThat(paper.get("disclaimer").asText()).isEqualTo("O resultado simulado não garante o resultado real.");
        assertThat(paper.get("templateId").asText()).isEqualTo(PnlViewsRows.STRATEGY);
        assertThat(paper.get("line").get("botId").asText())
                .isEqualTo(pair.paper().toString());
        JsonNode real = answer.get("real");
        assertThat(real.get("currency").asText()).isEqualTo("SOL");
        assertThat(real.has("disclaimer")).isFalse();
        assertThat(real.get("line").get("realizedLamports").isTextual()).isTrue();
        assertThat(response.getContentAsString())
                .doesNotContain(pair.person().wallet().address());
    }

    @Test
    void anIdOrAPeriodThatIsNotValidIs400() throws Exception {
        Pair pair = pair();

        for (String route : List.of(
                ROUTE + "?paperBotId=x&realBotId=" + pair.real(),
                ROUTE + "?realBotId=" + pair.real(),
                compare(pair.paper(), pair.real()) + "&period=RANGE",
                compare(pair.paper(), pair.real()) + "&period=RANGE&from=2026-11-02T00:00:00Z&to=2026-11-01T00:00:00Z",
                compare(pair.paper(), pair.real()) + "&tenantId=" + UUID.randomUUID())) {
            MockHttpServletResponse response = read(pair.person(), get(route));
            assertThat(response.getStatus()).as(route).isEqualTo(400);
            assertThat(body(response).get("code").asText()).isEqualTo("VALIDATION_FAILED");
        }
    }

    @Test
    void twoBotsOfThePersonWithDifferentStrategiesAre422WithoutAnyValue() throws Exception {
        Pair pair = pair();

        MockHttpServletResponse response = read(pair.person(), get(compare(pair.paper(), pair.otherReal())));

        assertThat(response.getStatus()).isEqualTo(422);
        JsonNode problem = body(response);
        assertThat(problem.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(problem.get("reason").asText()).isEqualTo("TEMPLATE_MISMATCH");
        assertThat(response.getContentAsString())
                .doesNotContain("realized")
                .doesNotContain("Lamports")
                .doesNotContain(PnlViewsRows.OTHER_STRATEGY)
                .doesNotContain("Outro");
    }

    @Test
    void aBotOfTheWrongModeIs404WithTheReasonOfItsSide() throws Exception {
        Pair pair = pair();

        MockHttpServletResponse paperAsReal = read(pair.person(), get(compare(pair.paper(), pair.paper())));
        MockHttpServletResponse realAsPaper = read(pair.person(), get(compare(pair.real(), pair.real())));

        assertThat(paperAsReal.getStatus()).isEqualTo(404);
        assertThat(body(paperAsReal).get("reason").asText()).isEqualTo("REAL_BOT");
        assertThat(realAsPaper.getStatus()).isEqualTo(404);
        assertThat(body(realAsPaper).get("reason").asText()).isEqualTo("PAPER_BOT");
    }

    @Test
    void withoutASessionIs401AndWithNewTermsPendingIs403() throws Exception {
        Pair pair = pair();

        assertThat(mvc.perform(get(compare(pair.paper(), pair.real())))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
        MockHttpServletResponse locked = read(staleTermsPerson(), get(compare(pair.paper(), pair.real())));
        assertThat(locked.getStatus()).isEqualTo(403);
        assertThat(body(locked).get("code").asText()).isEqualTo("TERMS_REACCEPTANCE_REQUIRED");
    }

    @Test
    void threeReadsWith200WriteNothing() throws Exception {
        Pair pair = pair();
        PnlViewsRows rows = new PnlViewsRows(jdbc);
        long before = rows.rowsOfTheReads();

        for (int i = 0; i < 3; i++) {
            assertThat(read(pair.person(), get(compare(pair.paper(), pair.real())))
                            .getStatus())
                    .isEqualTo(200);
        }

        assertThat(rows.rowsOfTheReads()).isEqualTo(before);
    }
}
