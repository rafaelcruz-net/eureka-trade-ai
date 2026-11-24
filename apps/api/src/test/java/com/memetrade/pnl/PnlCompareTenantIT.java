package com.memetrade.pnl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.memetrade.testsupport.AbstractPaperRealIT;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 261121-paper-real-unico U3, Etapa 9 (BR5.2, BR5.3, BR8.1, AC8.1.4, NFR3.1, NFR3.3): the ownership of the two bots of
 * C6 comes before the strategy. A REAL bot of another tenant AND of another strategy is 404 {@code REAL_BOT}, never 422;
 * the same with the PAPER side ({@code PAPER_BOT}); a bot of the wrong mode is 404. The body has no strategy, name nor
 * value. The negative test of the route C6 in {@code route-registry.yml}.
 */
@Tag("u3-pnl-views")
class PnlCompareTenantIT extends AbstractPaperRealIT {

    @Test
    void aBotOfAnotherTenantWithAnotherStrategyIs404NeverA422() throws Exception {
        Person person = signIn(false, false);
        Person stranger = signIn(false, false);
        assertThat(person.tenantId()).isNotEqualTo(stranger.tenantId());
        PnlViewsRows rows = new PnlViewsRows(jdbc);
        UUID paper = rows.paperBot(person.tenantId(), person.userId(), "Meu simulado", PnlViewsRows.STRATEGY);
        UUID real = rows.realBot(person.tenantId(), person.userId(), "Meu real", PnlViewsRows.STRATEGY);
        UUID strangerReal =
                rows.realBot(stranger.tenantId(), stranger.userId(), "Real do outro", PnlViewsRows.OTHER_STRATEGY);
        UUID strangerPaper =
                rows.paperBot(stranger.tenantId(), stranger.userId(), "Simulado do outro", PnlViewsRows.OTHER_STRATEGY);

        MockHttpServletResponse realSide =
                read(person, get("/api/v1/pnl/compare?paperBotId=" + paper + "&realBotId=" + strangerReal));
        MockHttpServletResponse paperSide =
                read(person, get("/api/v1/pnl/compare?paperBotId=" + strangerPaper + "&realBotId=" + real));

        assertThat(realSide.getStatus()).isEqualTo(404);
        assertThat(body(realSide).get("reason").asText()).isEqualTo("REAL_BOT");
        assertThat(paperSide.getStatus()).isEqualTo(404);
        assertThat(body(paperSide).get("reason").asText()).isEqualTo("PAPER_BOT");
        for (MockHttpServletResponse response : new MockHttpServletResponse[] {realSide, paperSide}) {
            assertThat(response.getContentAsString())
                    .doesNotContain(PnlViewsRows.OTHER_STRATEGY)
                    .doesNotContain(PnlViewsRows.STRATEGY)
                    .doesNotContain("do outro")
                    .doesNotContain("realized")
                    .doesNotContain(stranger.tenantId().toString());
        }
    }

    @Test
    void theOtherPersonOfTheSameStrategyIsStill404() throws Exception {
        Person person = signIn(false, false);
        Person stranger = signIn(false, false);
        PnlViewsRows rows = new PnlViewsRows(jdbc);
        UUID paper = rows.paperBot(person.tenantId(), person.userId(), "Meu simulado", PnlViewsRows.STRATEGY);
        UUID strangerReal = rows.realBot(stranger.tenantId(), stranger.userId(), "Real", PnlViewsRows.STRATEGY);

        MockHttpServletResponse response =
                read(person, get("/api/v1/pnl/compare?paperBotId=" + paper + "&realBotId=" + strangerReal));

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(body(response).get("reason").asText()).isEqualTo("REAL_BOT");
    }
}
