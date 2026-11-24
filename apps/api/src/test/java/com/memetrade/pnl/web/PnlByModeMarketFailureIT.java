package com.memetrade.pnl.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.testsupport.AbstractPaperRealIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 261121-paper-real-unico U3, Etapa 5 (test after the layer; BR3.3, NFR4.7, NFR4.13): a failure of the block asked by
 * {@code market} is a 200 with that block UNAVAILABLE, its code and the correlation id of the response, and one more
 * count of {@code pnl_mode_block_unavailable_total} of its market; never a 500.
 */
@Tag("u3-pnl-views")
class PnlByModeMarketFailureIT extends AbstractPaperRealIT {

    @Test
    void aFailureOfThePaperBlockAskedIsUnavailableWithItsCodeAndCorrelationId() throws Exception {
        Person person = signIn(false, false);
        faultyPaper.fault.set(Fault.FAIL);
        double before = unavailableBlocks("PAPER");

        MockHttpServletResponse response = read(person, get("/api/v1/pnl/by-mode?market=PAPER"));

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode paper = body(response).get("paper");
        assertThat(paper.get("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(paper.get("currency").asText()).isEqualTo("USD");
        assertThat(paper.get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(paper.get("correlationId").asText()).isEqualTo(response.getHeader("X-Correlation-Id"));
        assertThat(body(response).has("real")).isFalse();
        assertThat(unavailableBlocks("PAPER")).isEqualTo(before + 1);
    }

    @Test
    void aFailureOfTheRealBlockAskedIsUnavailableAndCountedOnlyInItsMarket() throws Exception {
        Person person = signIn(false, false);
        faultyReal.fault.set(Fault.FAIL);
        double real = unavailableBlocks("REAL");
        double paper = unavailableBlocks("PAPER");

        MockHttpServletResponse response = read(person, get("/api/v1/pnl/by-mode?market=REAL"));

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode block = body(response).get("real");
        assertThat(block.get("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(block.get("currency").asText()).isEqualTo("SOL");
        assertThat(block.get("correlationId").asText()).isEqualTo(response.getHeader("X-Correlation-Id"));
        assertThat(unavailableBlocks("REAL")).isEqualTo(real + 1);
        assertThat(unavailableBlocks("PAPER")).isEqualTo(paper);
    }

    @Test
    void aRealBlockPastItsDeadlineIsUnavailableAsTimeout() throws Exception {
        Person person = signIn(false, false);
        faultyReal.fault.set(Fault.SLEEP);
        double before = unavailableBlocks("REAL", "TIMEOUT");

        MockHttpServletResponse response = read(person, get("/api/v1/pnl/by-mode?market=REAL"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(body(response).get("real").get("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(unavailableBlocks("REAL", "TIMEOUT")).isEqualTo(before + 1);
    }
}
