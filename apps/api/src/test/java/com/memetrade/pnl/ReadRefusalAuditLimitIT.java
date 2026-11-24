package com.memetrade.pnl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.memetrade.testsupport.AbstractPaperRealIT;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 261121-paper-real-unico U3, Etapa 11 (test after the layer; D-NFR1, NFR1.10, NFR3.13, NFR3.14): the audit of the
 * refused reads through the real writer of the trail. Three 404 of another tenant of the same person in the same hour
 * (C5, the PAPER side and the REAL side of C6) write ONE {@code TENANT_MISMATCH}, with the route and the correlation id
 * of the first; a 422 writes its own {@code PNL_COMPARE_TEMPLATE_MISMATCH}; a 404 of an id that does not exist writes
 * nothing; another person writes one more. The events carry no name, strategy, value nor address.
 */
@Tag("u3-pnl-views")
class ReadRefusalAuditLimitIT extends AbstractPaperRealIT {

    @Test
    void threeRefusalsOfAnotherTenantInAnHourAreOneEventWithTheFirstRouteAndCorrelationId() throws Exception {
        Person person = signIn(false, false);
        Person stranger = signIn(false, false);
        PnlViewsRows rows = new PnlViewsRows(jdbc);
        UUID paper = rows.paperBot(person.tenantId(), person.userId(), "Meu simulado", PnlViewsRows.STRATEGY);
        UUID real = rows.realBot(person.tenantId(), person.userId(), "Meu real", PnlViewsRows.STRATEGY);
        UUID strangerReal = rows.realBot(stranger.tenantId(), stranger.userId(), "Dele", PnlViewsRows.STRATEGY);
        UUID strangerPaper = rows.paperBot(stranger.tenantId(), stranger.userId(), "Dele", PnlViewsRows.STRATEGY);

        MockHttpServletResponse first = read(person, get("/api/v1/bots/" + strangerReal + "/pnl/lines"));
        read(person, get("/api/v1/pnl/compare?paperBotId=" + strangerPaper + "&realBotId=" + real));
        read(person, get("/api/v1/pnl/compare?paperBotId=" + paper + "&realBotId=" + strangerReal));

        assertThat(first.getStatus()).isEqualTo(404);
        List<String> events = events(person.userId(), "TENANT_MISMATCH", 1);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst())
                .contains("\"route\": \"LINES\"")
                .contains(first.getHeader("X-Correlation-Id"))
                .doesNotContain("Dele")
                .doesNotContain(PnlViewsRows.STRATEGY)
                .doesNotContain(person.wallet().address());
    }

    @Test
    void a422WritesItsOwnEventAndA404OfAnUnknownIdWritesNothing() throws Exception {
        Person person = signIn(false, false);
        PnlViewsRows rows = new PnlViewsRows(jdbc);
        UUID paper = rows.paperBot(person.tenantId(), person.userId(), "S", PnlViewsRows.STRATEGY);
        UUID other = rows.realBot(person.tenantId(), person.userId(), "R", PnlViewsRows.OTHER_STRATEGY);

        assertThat(read(person, get("/api/v1/bots/" + UUID.randomUUID() + "/pnl/lines"))
                        .getStatus())
                .isEqualTo(404);
        assertThat(read(person, get("/api/v1/pnl/compare?paperBotId=" + paper + "&realBotId=" + other))
                        .getStatus())
                .isEqualTo(422);

        List<String> mismatches = events(person.userId(), "PNL_COMPARE_TEMPLATE_MISMATCH", 1);
        assertThat(mismatches).hasSize(1);
        assertThat(mismatches.getFirst())
                .doesNotContain(PnlViewsRows.OTHER_STRATEGY)
                .doesNotContain("realized");
        Thread.sleep(300);
        assertThat(events(person.userId(), "TENANT_MISMATCH", 0)).isEmpty();
    }

    @Test
    void tenRefusalsInARowAreOneEventAndAnotherPersonIsOneMore() throws Exception {
        Person person = signIn(false, false);
        Person other = signIn(false, false);
        Person stranger = signIn(false, false);
        UUID strangerReal =
                new PnlViewsRows(jdbc).realBot(stranger.tenantId(), stranger.userId(), "R", PnlViewsRows.STRATEGY);

        for (int i = 0; i < 10; i++) {
            assertThat(read(person, get("/api/v1/bots/" + strangerReal + "/pnl/lines"))
                            .getStatus())
                    .isEqualTo(404);
        }
        read(other, get("/api/v1/bots/" + strangerReal + "/pnl/lines"));

        assertThat(events(person.userId(), "TENANT_MISMATCH", 1)).hasSize(1);
        assertThat(events(other.userId(), "TENANT_MISMATCH", 1)).hasSize(1);
    }

    /** The events of the action of the person in the outbox, waiting up to 3 s for at least {@code expected}. */
    private List<String> events(UUID userId, String action, int expected) throws InterruptedException {
        List<String> found = List.of();
        for (int attempt = 0; attempt < 30; attempt++) {
            found = jdbc.sql("""
                            select payload::text from audit_outbox
                            where payload ->> 'action' = :action and payload ->> 'actor' = :actor
                            order by created_at""")
                    .param("action", action)
                    .param("actor", userId.toString())
                    .query(String.class)
                    .list();
            if (found.size() >= expected && expected > 0) {
                return found;
            }
            Thread.sleep(100);
        }
        return found;
    }
}
