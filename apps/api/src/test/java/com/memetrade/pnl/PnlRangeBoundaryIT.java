package com.memetrade.pnl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.testsupport.AbstractPaperRealIT;
import com.memetrade.testsupport.SyntheticIds;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 261121-paper-real-unico U3, Etapa 2, WRITTEN BEFORE THE CODE (BR2.4, BR6.1, AC7.1.3, AC7.2.3, NFR2.3, NFR4.7, NFR7.7):
 * the boundaries of the window RANGE of C4 in the two modes, over a seed of known result. {@code from} is included and
 * {@code to} excluded: an operation exactly at {@code from} counts, one exactly at {@code to} does not, one 1 ms before
 * {@code to} counts. The same pair of instants gives the same realized and fees in the line of the bot of C4 and in the
 * side of C6. The unrealized is always the one of now, in ALL and in RANGE. An incoherent period is refused with 400
 * {@code VALIDATION_FAILED} before any read.
 */
@Tag("u3-pnl-views")
class PnlRangeBoundaryIT extends AbstractPaperRealIT {

    private static final String BY_MODE = "/api/v1/pnl/by-mode";
    private static final String COMPARE = "/api/v1/pnl/compare";
    private static final long SOL = 1_000_000_000L;
    private static final Instant FROM = Instant.parse("2026-11-10T10:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-12T10:00:00Z");

    /** The seed: one PAPER bot and one REAL bot of the same strategy, with sales before, at and after the bounds. */
    private record Seeded(Person person, UUID paperBot, UUID realBot) {}

    private Seeded seed() throws Exception {
        Person person = signIn(true, true);
        PnlViewsRows rows = new PnlViewsRows(jdbc);
        UUID paperBot = rows.paperBot(person.tenantId(), person.userId(), "Simulado", PnlViewsRows.STRATEGY);
        UUID realBot = rows.realBot(person.tenantId(), person.userId(), "Real", PnlViewsRows.STRATEGY);
        // PAPER: 5 at from (in), 2 at to - 1 ms (in), 1 at to (out), 100 one day before from (out).
        rows.paperFill(person.tenantId(), person.userId(), paperBot, FROM.minusSeconds(86_400), "100.000000", "0");
        rows.paperFill(person.tenantId(), person.userId(), paperBot, FROM, "5.000000", "0.100000");
        rows.paperFill(person.tenantId(), person.userId(), paperBot, TO.minusMillis(1), "2.000000", "0.020000");
        rows.paperFill(person.tenantId(), person.userId(), paperBot, TO, "1.000000", "0.010000");
        // REAL: 400 units at an average cost of 1_000_000 lamports; three sales of 100 units at from, to - 1 ms and to.
        UUID wallet = activeWallet(person, 5 * SOL);
        String mint = SyntheticIds.mint();
        RealPnlSeed seed =
                new RealPnlSeed(jdbc, person.tenantId(), person.userId(), wallet, FROM.minusSeconds(3_600), 970_000);
        seed.buy(realBot, mint, 400, 400_000_000L, 4_000L);
        seed.at(FROM).sell(realBot, mint, 100, 150_000_000L, 1_000L);
        seed.at(TO.minusMillis(1)).sell(realBot, mint, 100, 120_000_000L, 2_000L);
        seed.at(TO).sell(realBot, mint, 100, 110_000_000L, 3_000L);
        return new Seeded(person, paperBot, realBot);
    }

    @Test
    void anOperationAtFromCountsOneAtToDoesNotAndOneMillisecondBeforeToCounts() throws Exception {
        Seeded seeded = seed();

        JsonNode answer = ok(seeded.person(), BY_MODE + range());

        assertThat(answer.get("period").get("kind").asText()).isEqualTo("RANGE");
        assertThat(Instant.parse(answer.get("period").get("from").asText())).isEqualTo(FROM);
        assertThat(Instant.parse(answer.get("period").get("to").asText())).isEqualTo(TO);
        JsonNode paper = line(answer.get("paper").get("bots"), seeded.paperBot());
        assertThat(new BigDecimal(paper.get("realized").asText())).isEqualByComparingTo("7.000000");
        JsonNode real = line(answer.get("real").get("bots"), seeded.realBot());
        // (150_000_000 - 100_000_000) + (120_000_000 - 100_000_000): the sale at "to" is out.
        assertThat(real.get("realizedLamports").asText()).isEqualTo("70000000");
        assertThat(real.get("feesLamports").asText()).isEqualTo("3000");
    }

    @Test
    void theSamePairGivesTheSameRealizedAndFeesInTheLineOfC4AndInTheSideOfC6() throws Exception {
        Seeded seeded = seed();

        JsonNode byMode = ok(seeded.person(), BY_MODE + range());
        JsonNode compare = ok(
                seeded.person(),
                COMPARE + "?paperBotId=" + seeded.paperBot() + "&realBotId=" + seeded.realBot() + "&period=RANGE&from="
                        + FROM + "&to=" + TO);

        JsonNode paperLine = line(byMode.get("paper").get("bots"), seeded.paperBot());
        JsonNode paperSide = compare.get("paper").get("line");
        assertThat(new BigDecimal(paperSide.get("realized").asText()))
                .isEqualByComparingTo(new BigDecimal(paperLine.get("realized").asText()));
        assertThat(new BigDecimal(paperSide.get("fees").asText()))
                .isEqualByComparingTo(new BigDecimal(paperLine.get("fees").asText()));
        JsonNode realLine = line(byMode.get("real").get("bots"), seeded.realBot());
        JsonNode realSide = compare.get("real").get("line");
        assertThat(Long.parseLong(realSide.get("realizedLamports").asText()))
                .isEqualTo(Long.parseLong(realLine.get("realizedLamports").asText()));
        assertThat(Long.parseLong(realSide.get("feesLamports").asText()))
                .isEqualTo(Long.parseLong(realLine.get("feesLamports").asText()));
        assertThat(compare.get("period")).isEqualTo(byMode.get("period"));
    }

    @Test
    void theUnrealizedIsTheOneOfNowInAllAndInRange() throws Exception {
        Seeded seeded = seed();

        JsonNode all = ok(seeded.person(), BY_MODE + "?period=ALL");
        JsonNode range = ok(seeded.person(), BY_MODE + range());

        assertThat(line(range.get("real").get("bots"), seeded.realBot()).get("unrealizedLamports"))
                .isEqualTo(line(all.get("real").get("bots"), seeded.realBot()).get("unrealizedLamports"));
        assertThat(line(range.get("paper").get("bots"), seeded.paperBot()).get("unrealized"))
                .isEqualTo(line(all.get("paper").get("bots"), seeded.paperBot()).get("unrealized"));
        assertThat(Instant.parse(range.get("real").get("unrealizedAsOf").asText()))
                .isEqualTo(clock.instant());
    }

    @Test
    void anIncoherentPeriodIs400ValidationFailed() throws Exception {
        Person person = signIn(false, false);

        assertValidation(person, BY_MODE + "?period=RANGE&to=" + TO);
        assertValidation(person, BY_MODE + "?period=RANGE&from=" + FROM);
        assertValidation(person, BY_MODE + "?period=RANGE&from=" + TO + "&to=" + FROM);
        assertValidation(person, BY_MODE + "?period=RANGE&from=" + FROM + "&to=" + FROM);
        assertValidation(person, BY_MODE + "?period=RANGE&from=" + FROM + "&to=" + FROM.plusSeconds(366L * 86_400 + 1));
        assertValidation(person, BY_MODE + "?period=ALL&from=" + FROM + "&to=" + TO);
        assertValidation(person, BY_MODE + "?from=" + FROM + "&to=" + TO);
        assertThat(read(person, get(BY_MODE + "?period=RANGE&from=" + FROM + "&to=" + FROM.plusSeconds(366L * 86_400)))
                        .getStatus())
                .isEqualTo(200);
    }

    private void assertValidation(Person person, String route) throws Exception {
        MockHttpServletResponse response = read(person, get(route));
        assertThat(response.getStatus()).as(route).isEqualTo(400);
        assertThat(body(response).get("code").asText()).isEqualTo("VALIDATION_FAILED");
    }

    private static String range() {
        return "?period=RANGE&from=" + FROM + "&to=" + TO;
    }

    static JsonNode line(JsonNode bots, UUID botId) {
        for (JsonNode bot : bots) {
            if (bot.get("botId").asText().equals(botId.toString())) {
                return bot;
            }
        }
        throw new AssertionError("no line of the bot " + botId + " in " + bots);
    }

    private JsonNode ok(Person person, String route) throws Exception {
        MockHttpServletResponse response = read(person, get(route));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return body(response);
    }
}
