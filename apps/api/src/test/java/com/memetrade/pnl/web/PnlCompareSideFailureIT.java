package com.memetrade.pnl.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.pnl.PnlViewsRows;
import com.memetrade.testsupport.AbstractPaperRealIT;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 261121-paper-real-unico U3, Etapa 9 (test after the layer; BR5.5, NFR4.9, NFR3.11): a side of C6 that fails is
 * UNAVAILABLE with the correlation id of the response, the other side goes on, and the answer is always 200; each
 * failure counts {@code pnl.compare.side_unavailable} of its market, never the series of C4.
 */
@Tag("u3-pnl-views")
class PnlCompareSideFailureIT extends AbstractPaperRealIT {

    private String route;
    private Person person;

    private void pair() throws Exception {
        person = signIn(false, false);
        PnlViewsRows rows = new PnlViewsRows(jdbc);
        UUID paper = rows.paperBot(person.tenantId(), person.userId(), "Simulado", PnlViewsRows.STRATEGY);
        UUID real = rows.realBot(person.tenantId(), person.userId(), "Real", PnlViewsRows.STRATEGY);
        route = "/api/v1/pnl/compare?paperBotId=" + paper + "&realBotId=" + real;
    }

    @Test
    void aFailureOfThePaperSideLeavesTheRealSide() throws Exception {
        pair();
        faultyPaper.lineFault.set(Fault.FAIL);
        double before = sides("PAPER");
        double blocks = unavailableBlocks("PAPER");

        MockHttpServletResponse response = read(person, get(route));

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode answer = body(response);
        assertThat(answer.get("paper").get("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(answer.get("paper").get("currency").asText()).isEqualTo("USD");
        assertThat(answer.get("paper").get("correlationId").asText()).isEqualTo(response.getHeader("X-Correlation-Id"));
        assertThat(answer.get("real").get("status").asText()).isEqualTo("AVAILABLE");
        assertThat(sides("PAPER")).isEqualTo(before + 1);
        assertThat(unavailableBlocks("PAPER")).isEqualTo(blocks);
    }

    @Test
    void aFailureOfTheRealSideLeavesThePaperSide() throws Exception {
        pair();
        faultyReal.botFault.set(Fault.FAIL);
        double before = sides("REAL");

        JsonNode answer = body(read(person, get(route)));

        assertThat(answer.get("real").get("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(answer.get("real").get("currency").asText()).isEqualTo("SOL");
        assertThat(answer.get("paper").get("status").asText()).isEqualTo("AVAILABLE");
        assertThat(sides("REAL")).isEqualTo(before + 1);
    }

    @Test
    void bothSidesFailingIsStill200WithTwoUnavailableSides() throws Exception {
        pair();
        faultyPaper.lineFault.set(Fault.FAIL);
        faultyReal.botFault.set(Fault.SLEEP);

        MockHttpServletResponse response = read(person, get(route));

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode answer = body(response);
        assertThat(answer.get("paper").get("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(answer.get("real").get("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(meters.find("pnl.compare.side_unavailable")
                        .tag("market", "REAL")
                        .tag("cause", "TIMEOUT")
                        .counter()
                        .count())
                .isPositive();
    }

    private double sides(String market) {
        return meters.find("pnl.compare.side_unavailable").tag("market", market).counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count)
                .sum();
    }
}
