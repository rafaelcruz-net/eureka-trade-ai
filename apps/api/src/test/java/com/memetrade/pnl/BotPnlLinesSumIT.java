package com.memetrade.pnl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.testsupport.AbstractPaperRealIT;
import com.memetrade.testsupport.SyntheticIds;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 261121-paper-real-unico U3, Etapa 3, WRITTEN BEFORE THE CODE (BR4.3, BR4.6, AC3.10.1, AC3.10.2, NFR2.3 (a), NFR7.7):
 * the exact sum of the pages of C5. A scenario of known result for one REAL bot with buys, sales, a liquidation, a
 * conditional sale and a part sold by another bot, more than 100 lines; every page is read with {@code limit=50} until
 * {@code nextCursor} is null; the sum with {@code Math.addExact} of {@code realizedLamports} and of {@code feeLamports}
 * is equal to the realized and the fees of the line of the bot in the REAL block of C4 with {@code period=ALL}; the
 * difference is 0, and the number of lines is the one of the scenario.
 */
@Tag("u3-pnl-views")
class BotPnlLinesSumIT extends AbstractPaperRealIT {

    private static final long SOL = 1_000_000_000L;

    @Test
    void theSumOfEveryPageIsExactlyTheLineOfTheBotInC4() throws Exception {
        Person person = signIn(true, true);
        PnlViewsRows rows = new PnlViewsRows(jdbc);
        UUID bot = rows.realBot(person.tenantId(), person.userId(), "Alvo", PnlViewsRows.STRATEGY);
        UUID other = rows.realBot(person.tenantId(), person.userId(), "Outro", PnlViewsRows.STRATEGY);
        UUID wallet = activeWallet(person, 5 * SOL);
        String mint = SyntheticIds.mint();
        RealPnlSeed seed = new RealPnlSeed(
                jdbc,
                person.tenantId(),
                person.userId(),
                wallet,
                clock.instant().minus(Duration.ofDays(5)),
                990_000);
        Map<String, Integer> expected = new java.util.TreeMap<>();
        for (int round = 0; round < 40; round++) {
            seed.buy(bot, mint, 10, 10_000_000L + round * 7_001L, 1_000L + round);
            seed.sell(bot, mint, 5, 6_000_000L + round * 3_331L, 500L + round);
            expected.merge("BOT", 2, Integer::sum);
        }
        // The other bot buys 100 and sells 150: the excess of 50 is sold from the target (SOLD_BY_OTHER_BOT).
        seed.buy(other, mint, 100, 120_000_000L, 2_000L);
        seed.sell(other, mint, 150, 160_000_000L, 3_000L);
        expected.merge("SOLD_BY_OTHER_BOT", 1, Integer::sum);
        // Only the target holds now: the liquidation and the conditional are its parts.
        seed.liquidation(mint, 20, 21_000_000L, 4_000L);
        expected.merge("LIQUIDATION", 1, Integer::sum);
        seed.conditional(mint, 10, 9_000_000L, 5_000L);
        expected.merge("CONDITIONAL", 1, Integer::sum);
        for (int round = 0; round < 20; round++) {
            seed.buy(bot, mint, 3, 3_300_000L + round, 700L);
            expected.merge("BOT", 1, Integer::sum);
        }

        long realized = 0;
        long fees = 0;
        int lines = 0;
        Map<String, Integer> origins = new java.util.TreeMap<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = ok(
                    person,
                    "/api/v1/bots/" + bot + "/pnl/lines?limit=50" + (cursor == null ? "" : "&cursor=" + cursor));
            assertThat(page.get("currency").asText()).isEqualTo("SOL");
            assertThat(page.get("items").size()).isLessThanOrEqualTo(50);
            for (JsonNode item : page.get("items")) {
                realized = Math.addExact(
                        realized, Long.parseLong(item.get("realizedLamports").asText()));
                fees = Math.addExact(
                        fees, Long.parseLong(item.get("feeLamports").asText()));
                origins.merge(item.get("origin").asText(), 1, Integer::sum);
                lines++;
            }
            cursor = page.get("nextCursor").isNull()
                    ? null
                    : page.get("nextCursor").asText();
            pages++;
        } while (cursor != null && pages < 10);

        JsonNode line = PnlRangeBoundaryIT.line(
                ok(person, "/api/v1/pnl/by-mode?market=REAL&period=ALL")
                        .get("real")
                        .get("bots"),
                bot);
        assertThat(cursor).isNull();
        assertThat(pages).isEqualTo(3);
        assertThat(Math.subtractExact(
                        realized, Long.parseLong(line.get("realizedLamports").asText())))
                .isZero();
        assertThat(Math.subtractExact(
                        fees, Long.parseLong(line.get("feesLamports").asText())))
                .isZero();
        assertThat(lines).isEqualTo(103);
        assertThat(origins).isEqualTo(expected);
    }

    private JsonNode ok(Person person, String route) throws Exception {
        MockHttpServletResponse response = read(person, get(route));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return body(response);
    }
}
