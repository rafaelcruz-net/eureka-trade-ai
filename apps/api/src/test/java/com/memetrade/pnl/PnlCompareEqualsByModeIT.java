package com.memetrade.pnl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.testsupport.AbstractPaperRealIT;
import com.memetrade.testsupport.SyntheticIds;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 261121-paper-real-unico U3, Etapa 2, WRITTEN BEFORE THE CODE (BR5.4, BR6.1, BR6.2, AC8.1.1, AC8.1.2, NFR2.3, NFR4.1,
 * NFR7.7): the sides of C6 are the lines of the same bots in C4, by the same calculation, never a third number. With
 * ALL and with a RANGE of 30 days, the total, the realized, the unrealized and the fees of the PAPER side are equal
 * ({@code compareTo == 0}) to the line of the bot in the PAPER block of C4, and the REAL side to the line in the REAL
 * block, as {@code long}; the difference is 0.
 */
@Tag("u3-pnl-views")
class PnlCompareEqualsByModeIT extends AbstractPaperRealIT {

    private static final long SOL = 1_000_000_000L;

    @Test
    void withAllTheSidesAreTheLinesOfC4() throws Exception {
        Seeded seeded = seed();

        assertSidesEqualLines(seeded, "?period=ALL", "&period=ALL");
    }

    @Test
    void withARangeOfThirtyDaysTheSidesAreTheLinesOfC4() throws Exception {
        Seeded seeded = seed();
        Instant to = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant from = to.minus(Duration.ofDays(30));
        String period = "period=RANGE&from=" + from + "&to=" + to;

        assertSidesEqualLines(seeded, "?" + period, "&" + period);
    }

    private record Seeded(Person person, UUID paperBot, UUID realBot) {}

    /** Operations 40 days ago (outside the RANGE) and in the last 10 days, in the two modes. */
    private Seeded seed() throws Exception {
        Person person = signIn(true, true);
        PnlViewsRows rows = new PnlViewsRows(jdbc);
        UUID paperBot = rows.paperBot(person.tenantId(), person.userId(), "Simulado", PnlViewsRows.STRATEGY);
        rows.paperBot(person.tenantId(), person.userId(), "Outro simulado", PnlViewsRows.STRATEGY);
        UUID realBot = rows.realBot(person.tenantId(), person.userId(), "Real", PnlViewsRows.STRATEGY);
        UUID otherReal = rows.realBot(person.tenantId(), person.userId(), "Outro real", PnlViewsRows.STRATEGY);
        Instant now = clock.instant();
        rows.paperFill(person.tenantId(), person.userId(), paperBot, now.minus(Duration.ofDays(40)), "9.500000", "0.1");
        rows.paperFill(person.tenantId(), person.userId(), paperBot, now.minus(Duration.ofDays(5)), "-1.250000", "0.2");
        rows.paperFill(person.tenantId(), person.userId(), paperBot, now.minus(Duration.ofHours(3)), "3.333333", "0.3");
        UUID wallet = activeWallet(person, 5 * SOL);
        String mint = SyntheticIds.mint();
        RealPnlSeed seed = new RealPnlSeed(
                jdbc, person.tenantId(), person.userId(), wallet, now.minus(Duration.ofDays(41)), 980_000);
        seed.buy(realBot, mint, 300, 300_000_000L, 5_000L);
        seed.buy(otherReal, mint, 100, 120_000_000L, 1_000L);
        seed.at(now.minus(Duration.ofDays(40))).sell(realBot, mint, 100, 130_000_000L, 2_000L);
        seed.at(now.minus(Duration.ofDays(4))).sell(realBot, mint, 50, 40_000_000L, 1_500L);
        seed.at(now.minus(Duration.ofDays(1))).liquidation(mint, 100, 105_000_000L, 4_000L);
        return new Seeded(person, paperBot, realBot);
    }

    private void assertSidesEqualLines(Seeded seeded, String byModePeriod, String comparePeriod) throws Exception {
        JsonNode byMode = ok(seeded.person(), "/api/v1/pnl/by-mode" + byModePeriod);
        JsonNode compare = ok(
                seeded.person(),
                "/api/v1/pnl/compare?paperBotId=" + seeded.paperBot() + "&realBotId=" + seeded.realBot()
                        + comparePeriod);

        JsonNode paperLine = PnlRangeBoundaryIT.line(byMode.get("paper").get("bots"), seeded.paperBot());
        JsonNode paperSide = compare.get("paper").get("line");
        assertThat(paperSide.get("botId").asText()).isEqualTo(seeded.paperBot().toString());
        assertThat(decimal(paperSide, "realized").compareTo(decimal(paperLine, "realized")))
                .isZero();
        assertThat(decimal(paperSide, "fees").compareTo(decimal(paperLine, "fees")))
                .isZero();
        assertThat(paperSide.get("total")).isEqualTo(paperLine.get("total"));
        assertThat(paperSide.get("unrealized")).isEqualTo(paperLine.get("unrealized"));

        JsonNode realLine = PnlRangeBoundaryIT.line(byMode.get("real").get("bots"), seeded.realBot());
        JsonNode realSide = compare.get("real").get("line");
        assertThat(realSide.get("botId").asText()).isEqualTo(seeded.realBot().toString());
        assertThat(Math.subtractExact(lamports(realSide, "realizedLamports"), lamports(realLine, "realizedLamports")))
                .isZero();
        assertThat(Math.subtractExact(lamports(realSide, "feesLamports"), lamports(realLine, "feesLamports")))
                .isZero();
        assertThat(realSide.get("totalLamports")).isEqualTo(realLine.get("totalLamports"));
        assertThat(realSide.get("unrealizedLamports")).isEqualTo(realLine.get("unrealizedLamports"));
        assertThat(realSide.get("prorated")).isEqualTo(realLine.get("prorated"));
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        return new BigDecimal(node.get(field).asText());
    }

    private static long lamports(JsonNode node, String field) {
        return Long.parseLong(node.get(field).asText());
    }

    private JsonNode ok(Person person, String route) throws Exception {
        MockHttpServletResponse response = read(person, get(route));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return body(response);
    }
}
