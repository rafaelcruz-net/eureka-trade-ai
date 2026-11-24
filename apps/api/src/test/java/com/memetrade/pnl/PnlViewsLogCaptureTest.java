package com.memetrade.pnl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.memetrade.pnl.web.PnlController;
import com.memetrade.testsupport.AbstractPaperRealIT;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 261121-paper-real-unico U3, Etapa 12 (test after the layer; NFR3.7, NFR3.10): the events of the log of the routes of
 * the U3 ({@code PNL_READ_NOT_FOUND}, {@code PNL_COMPARE_TEMPLATE_MISMATCH}, {@code PNL_COMPARE_SIDE_UNAVAILABLE},
 * {@code PNL_BOT_LINES_UNAVAILABLE}) are written once per event with the correlation id of the response in the MDC, and
 * never carry an address, a strategy of another person, a token, an {@code Authorization}, a nonce or a signature.
 * ({@code PNL_READ_AUDIT_FAILED} is covered by {@code ReadRefusalAuditTest}.)
 */
@Tag("u3-pnl-views")
class PnlViewsLogCaptureTest extends AbstractPaperRealIT {

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(PnlController.class);

    @BeforeEach
    void capture() {
        logs.start();
        logger.addAppender(logs);
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void release() {
        logger.detachAppender(logs);
    }

    @Test
    void eachEventIsLoggedOnceWithTheCorrelationIdAndNothingSensitive() throws Exception {
        Person person = signIn(false, false);
        Person stranger = signIn(false, false);
        PnlViewsRows rows = new PnlViewsRows(jdbc);
        UUID paper = rows.paperBot(person.tenantId(), person.userId(), "S", PnlViewsRows.STRATEGY);
        UUID real = rows.realBot(person.tenantId(), person.userId(), "R", PnlViewsRows.STRATEGY);
        UUID other = rows.realBot(person.tenantId(), person.userId(), "O", PnlViewsRows.OTHER_STRATEGY);
        UUID strangerReal = rows.realBot(stranger.tenantId(), stranger.userId(), "X", PnlViewsRows.OTHER_STRATEGY);

        MockHttpServletResponse notFound = read(person, get("/api/v1/bots/" + strangerReal + "/pnl/lines"));
        MockHttpServletResponse mismatch =
                read(person, get("/api/v1/pnl/compare?paperBotId=" + paper + "&realBotId=" + other));
        faultyReal.botFault.set(Fault.FAIL);
        MockHttpServletResponse side =
                read(person, get("/api/v1/pnl/compare?paperBotId=" + paper + "&realBotId=" + real));
        MockHttpServletResponse lines = read(person, get("/api/v1/bots/" + real + "/pnl/lines"));

        assertOnce("event=PNL_READ_NOT_FOUND route=LINES side=NONE reason=TENANT_MISMATCH", notFound);
        assertOnce("event=PNL_COMPARE_TEMPLATE_MISMATCH", mismatch);
        assertOnce("event=PNL_COMPARE_SIDE_UNAVAILABLE market=REAL cause=ERROR", side);
        assertOnce("event=PNL_BOT_LINES_UNAVAILABLE cause=IllegalStateException", lines);
        for (ILoggingEvent event : logs.list) {
            String text = event.getFormattedMessage() + event.getMDCPropertyMap();
            assertThat(text)
                    .doesNotContain(person.wallet().address())
                    .doesNotContain(PnlViewsRows.OTHER_STRATEGY)
                    .doesNotContain("Bearer")
                    .doesNotContainIgnoringCase("authorization")
                    .doesNotContainIgnoringCase("nonce")
                    .doesNotContainIgnoringCase("signature");
        }
    }

    private void assertOnce(String message, MockHttpServletResponse response) {
        List<ILoggingEvent> found = logs.list.stream()
                .filter(event -> event.getFormattedMessage().equals(message))
                .toList();
        assertThat(found).as(message).hasSize(1);
        assertThat(found.getFirst().getMDCPropertyMap()).containsValue(response.getHeader("X-Correlation-Id"));
    }
}
