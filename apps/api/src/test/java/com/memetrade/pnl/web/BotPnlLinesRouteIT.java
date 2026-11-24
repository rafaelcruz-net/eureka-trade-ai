package com.memetrade.pnl.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.memetrade.pnl.PnlViewsRows;
import com.memetrade.pnl.RealPnlSeed;
import com.memetrade.pnl.api.RealPnlLineOrigin;
import com.memetrade.pnl.application.BotLinesCursor;
import com.memetrade.testsupport.AbstractPaperRealIT;
import com.memetrade.testsupport.SyntheticIds;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 261121-paper-real-unico U3, Etapa 8 (test after the layer; BR4.3, BR4.5, BR8.1, BR8.2, AC3.10.1, AC3.10.3, NFR1.3,
 * NFR3.2, NFR3.6, NFR3.7, NFR4.13): the route C5. A malformed cursor, a cursor of another bot or version, and a limit
 * outside 1 to 100 are 400 before any read; a bot of another tenant, an unknown id and a PAPER bot of the person are the
 * SAME 404, byte for byte but the correlation id; a failure of the pass is 503 with the correlation id; 401 and 403 of
 * the terms; no address of the bot wallet in the body; three reads write nothing.
 */
@Tag("u3-pnl-views")
class BotPnlLinesRouteIT extends AbstractPaperRealIT {

    private static final long SOL = 1_000_000_000L;

    private record Story(Person person, UUID bot, UUID wallet) {}

    private Story story() throws Exception {
        Person person = signIn(true, true);
        UUID bot = new PnlViewsRows(jdbc).realBot(person.tenantId(), person.userId(), "Real", PnlViewsRows.STRATEGY);
        UUID wallet = activeWallet(person, 5 * SOL);
        RealPnlSeed seed = new RealPnlSeed(
                jdbc,
                person.tenantId(),
                person.userId(),
                wallet,
                clock.instant().minus(Duration.ofDays(1)),
                4_000_000);
        String mint = SyntheticIds.mint();
        seed.buy(bot, mint, 10, 10_000_000L, 1_000L);
        seed.sell(bot, mint, 5, 7_000_000L, 500L);
        return new Story(person, bot, wallet);
    }

    private static String route(UUID bot) {
        return "/api/v1/bots/" + bot + "/pnl/lines";
    }

    @Test
    void theFirstPageHasTheLinesNewestFirstWithTheirOriginAndNoAddress() throws Exception {
        Story story = story();

        MockHttpServletResponse response = read(story.person(), get(route(story.bot())));

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode page = body(response);
        assertThat(page.get("currency").asText()).isEqualTo("SOL");
        assertThat(page.get("nextCursor").isNull()).isTrue();
        assertThat(page.get("items")).hasSize(2);
        assertThat(page.get("items").get(0).get("side").asText()).isEqualTo("SELL");
        assertThat(page.get("items").get(1).get("side").asText()).isEqualTo("BUY");
        assertThat(page.get("items").get(1).get("realizedLamports").asText()).isEqualTo("0");
        assertThat(page.get("items").get(0).get("origin").asText()).isEqualTo("BOT");
        assertThat(response.getContentAsString())
                .doesNotContain(new PnlViewsRows(jdbc).botWalletAddress(story.wallet()))
                .doesNotContain(story.person().wallet().address());
    }

    @Test
    void aMalformedCursorACursorOfAnotherBotOrVersionAndABadLimitAre400() throws Exception {
        Story story = story();
        String ofAnotherBot = BotLinesCursor.encode(UUID.randomUUID(), "k", RealPnlLineOrigin.BOT);
        byte[] raw = Base64.getUrlDecoder().decode(BotLinesCursor.encode(story.bot(), "k", RealPnlLineOrigin.BOT));
        raw[0] = 0x07;
        String ofAnotherVersion = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        for (String query : List.of(
                "?cursor=abc",
                "?cursor=" + ofAnotherBot,
                "?cursor=" + ofAnotherVersion,
                "?limit=0",
                "?limit=101",
                "?limit=x",
                "?tenantId=" + UUID.randomUUID())) {
            MockHttpServletResponse response = read(story.person(), get(route(story.bot()) + query));
            assertThat(response.getStatus()).as(query).isEqualTo(400);
            assertThat(body(response).get("code").asText()).isEqualTo("VALIDATION_FAILED");
            assertThat(response.getContentAsString()).doesNotContain(ofAnotherBot);
        }
        String unknownAnchor = BotLinesCursor.encode(story.bot(), "no-such-line", RealPnlLineOrigin.BOT);
        MockHttpServletResponse stale = read(story.person(), get(route(story.bot()) + "?cursor=" + unknownAnchor));
        assertThat(stale.getStatus()).isEqualTo(400);
        assertThat(body(stale).get("fields").get(0).get("field").asText()).isEqualTo("cursor");
    }

    @Test
    void anotherTenantAnUnknownIdAndAPaperBotAreTheSame404() throws Exception {
        Story owner = story();
        Person person = signIn(true, true);
        UUID paper =
                new PnlViewsRows(jdbc).paperBot(person.tenantId(), person.userId(), "Simulado", PnlViewsRows.STRATEGY);

        List<JsonNode> bodies = new java.util.ArrayList<>();
        for (UUID bot : List.of(owner.bot(), UUID.randomUUID(), paper)) {
            MockHttpServletResponse response = read(person, get(route(bot)));
            assertThat(response.getStatus()).isEqualTo(404);
            ObjectNode problem = (ObjectNode) body(response);
            assertThat(problem.get("correlationId").asText()).isEqualTo(response.getHeader("X-Correlation-Id"));
            problem.remove("correlationId");
            bodies.add(problem);
        }

        assertThat(bodies.get(1)).isEqualTo(bodies.get(0));
        assertThat(bodies.get(2)).isEqualTo(bodies.get(0));
        assertThat(bodies.get(0).toString())
                .doesNotContain("Real")
                .doesNotContain(owner.bot().toString());
    }

    @Test
    void aFailureOfThePassIs503WithTheCorrelationId() throws Exception {
        Story story = story();
        faultyReal.botFault.set(Fault.FAIL);

        MockHttpServletResponse response = read(story.person(), get(route(story.bot())));

        assertThat(response.getStatus()).isEqualTo(503);
        JsonNode problem = body(response);
        assertThat(problem.get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(problem.get("correlationId").asText()).isEqualTo(response.getHeader("X-Correlation-Id"));
        assertThat(response.getHeader("Retry-After")).isNull();
    }

    @Test
    void withoutASessionIs401AndWithNewTermsPendingIs403() throws Exception {
        Story story = story();

        assertThat(mvc.perform(get(route(story.bot())))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
        MockHttpServletResponse locked = read(staleTermsPerson(), get(route(story.bot())));
        assertThat(locked.getStatus()).isEqualTo(403);
        assertThat(body(locked).get("code").asText()).isEqualTo("TERMS_REACCEPTANCE_REQUIRED");
    }

    @Test
    void threeReadsWriteNothing() throws Exception {
        Story story = story();
        PnlViewsRows rows = new PnlViewsRows(jdbc);
        long before = rows.rowsOfTheReads();

        for (int i = 0; i < 3; i++) {
            assertThat(read(story.person(), get(route(story.bot()))).getStatus())
                    .isEqualTo(200);
        }

        assertThat(rows.rowsOfTheReads()).isEqualTo(before);
    }
}
