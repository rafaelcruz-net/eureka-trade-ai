package com.memetrade.pnl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.memetrade.testsupport.AbstractPaperRealIT;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 261121-paper-real-unico U3, Etapa 12 (test after the layer; NFR3.9, NFR3.12): the same correlation id of the response
 * is in the RFC 7807 body (404 of C5, 422 of C6), in the UNAVAILABLE side of C6, in the UNAVAILABLE block of C4 with
 * {@code market}, and in the audit event of the refusal.
 */
@Tag("u3-pnl-views")
class PnlViewsCorrelationIT extends AbstractPaperRealIT {

    @Test
    void theCorrelationIdOfTheResponseIsInTheProblemTheSideTheBlockAndTheEvent() throws Exception {
        Person person = signIn(false, false);
        Person stranger = signIn(false, false);
        PnlViewsRows rows = new PnlViewsRows(jdbc);
        UUID paper = rows.paperBot(person.tenantId(), person.userId(), "S", PnlViewsRows.STRATEGY);
        UUID real = rows.realBot(person.tenantId(), person.userId(), "R", PnlViewsRows.STRATEGY);
        UUID other = rows.realBot(person.tenantId(), person.userId(), "O", PnlViewsRows.OTHER_STRATEGY);
        UUID strangerReal = rows.realBot(stranger.tenantId(), stranger.userId(), "X", PnlViewsRows.STRATEGY);

        MockHttpServletResponse notFound = read(person, get("/api/v1/bots/" + strangerReal + "/pnl/lines"));
        assertThat(body(notFound).get("correlationId").asText()).isEqualTo(id(notFound));

        MockHttpServletResponse mismatch =
                read(person, get("/api/v1/pnl/compare?paperBotId=" + paper + "&realBotId=" + other));
        assertThat(mismatch.getStatus()).isEqualTo(422);
        assertThat(body(mismatch).get("correlationId").asText()).isEqualTo(id(mismatch));

        faultyReal.botFault.set(Fault.FAIL);
        MockHttpServletResponse side =
                read(person, get("/api/v1/pnl/compare?paperBotId=" + paper + "&realBotId=" + real));
        assertThat(body(side).get("real").get("correlationId").asText()).isEqualTo(id(side));

        faultyReal.fault.set(Fault.FAIL);
        MockHttpServletResponse block = read(person, get("/api/v1/pnl/by-mode?market=REAL"));
        assertThat(body(block).get("real").get("correlationId").asText()).isEqualTo(id(block));

        String event = null;
        for (int attempt = 0; attempt < 30 && event == null; attempt++) {
            event = jdbc.sql("""
                            select payload::text from audit_outbox
                            where payload ->> 'action' = 'TENANT_MISMATCH' and payload ->> 'actor' = :actor""")
                    .param("actor", person.userId().toString())
                    .query(String.class)
                    .optional()
                    .orElse(null);
            if (event == null) {
                Thread.sleep(100);
            }
        }
        assertThat(event).isNotNull().contains(id(notFound));
    }

    private static String id(MockHttpServletResponse response) {
        return response.getHeader("X-Correlation-Id");
    }
}
