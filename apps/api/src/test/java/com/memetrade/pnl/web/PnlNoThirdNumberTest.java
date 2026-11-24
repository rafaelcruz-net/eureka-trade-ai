package com.memetrade.pnl.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.memetrade.pnl.api.PnlMarket;
import com.memetrade.pnl.modeview.BlockResult;
import com.memetrade.pnl.web.PnlDtos.PnlAmountDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 261121-paper-real-unico U3, Etapa 2, WRITTEN BEFORE THE CODE (BR3.1, BR6.1, BR6.2, AC4.1.2, AC8.1.2, NFR4.1, NFR4.2,
 * NFR4.4, NFR7.7): a property over random answers, 3 fixed seeds and 200 answers per seed and shape. The JSON of C4
 * without {@code market}, with {@code market=PAPER}, with {@code market=REAL}, and the JSON of C6, have only the keys
 * {@code period}, {@code paper} and {@code real}; no number lives outside a block or a side; C6 has no field of a
 * difference, a percentage, a ranking or a winner; the key of a block not asked is ABSENT, never {@code null}.
 */
class PnlNoThirdNumberTest {

    private static final int ANSWERS = 200;
    private static final List<String> FORBIDDEN = List.of(
            "difference",
            "diff",
            "delta",
            "percent",
            "percentage",
            "ratio",
            "rank",
            "ranking",
            "winner",
            "best",
            "grandtotal",
            "overall",
            "sum",
            "combined");

    private final ObjectMapper json =
            new ObjectMapper().registerModule(new JavaTimeModule()).registerModule(new Jdk8Module());

    @ParameterizedTest
    @ValueSource(longs = {7L, 20261123L, 424242L})
    void c4WithoutMarketHasOnlyThePeriodAndTheTwoBlocks(long seed) throws Exception {
        Random random = new Random(seed);
        for (int i = 0; i < ANSWERS; i++) {
            JsonNode answer = json.valueToTree(ModePnlDtos.PnlByModeDto.of(
                    period(random), Optional.of(paperResult(random)), Optional.of(realResult(random))));

            assertThat(keys(answer)).containsExactly("period", "paper", "real");
            assertThat(answer.get("paper").isNull()).isFalse();
            assertThat(answer.get("real").isNull()).isFalse();
            assertNoNumberOutsideTheBlocks(answer);
            assertNoForbiddenField(answer);
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {7L, 20261123L, 424242L})
    void c4WithMarketPaperHasNoKeyReal(long seed) throws Exception {
        Random random = new Random(seed);
        for (int i = 0; i < ANSWERS; i++) {
            JsonNode answer = json.valueToTree(
                    ModePnlDtos.PnlByModeDto.of(period(random), Optional.of(paperResult(random)), Optional.empty()));

            assertThat(keys(answer)).containsExactly("period", "paper");
            assertThat(answer.has("real")).isFalse();
            assertNoNumberOutsideTheBlocks(answer);
            assertNoForbiddenField(answer);
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {7L, 20261123L, 424242L})
    void c4WithMarketRealHasNoKeyPaper(long seed) throws Exception {
        Random random = new Random(seed);
        for (int i = 0; i < ANSWERS; i++) {
            JsonNode answer = json.valueToTree(
                    ModePnlDtos.PnlByModeDto.of(period(random), Optional.empty(), Optional.of(realResult(random))));

            assertThat(keys(answer)).containsExactly("period", "real");
            assertThat(answer.has("paper")).isFalse();
            assertNoNumberOutsideTheBlocks(answer);
            assertNoForbiddenField(answer);
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {7L, 20261123L, 424242L})
    void c6HasOnlyThePeriodAndTheTwoSidesAndNoDifference(long seed) throws Exception {
        Random random = new Random(seed);
        for (int i = 0; i < ANSWERS; i++) {
            JsonNode answer = json.valueToTree(
                    ModePnlDtos.PnlComparisonDto.of(period(random), paperSide(random), realSide(random)));

            assertThat(keys(answer)).containsExactly("period", "paper", "real");
            assertNoNumberOutsideTheBlocks(answer);
            assertNoForbiddenField(answer);
            if ("AVAILABLE".equals(answer.get("paper").get("status").asText())) {
                assertThat(keys(answer.get("paper")))
                        .containsExactly(
                                "status", "market", "currency", "disclaimer", "createdAt", "templateId", "line");
            }
            if ("AVAILABLE".equals(answer.get("real").get("status").asText())) {
                assertThat(keys(answer.get("real")))
                        .containsExactly("status", "market", "currency", "createdAt", "templateId", "line");
                assertThat(answer.get("real").has("disclaimer")).isFalse();
            }
        }
    }

    // ----- the property -----

    /** Only {@code kind}, {@code from} and {@code to} in the period; no number nor amount outside the blocks. */
    private static void assertNoNumberOutsideTheBlocks(JsonNode answer) {
        JsonNode period = answer.get("period");
        assertThat(keys(period)).isSubsetOf("kind", "from", "to");
        assertThat(period.get("kind").asText()).isIn("ALL", "RANGE");
        Iterator<String> names = answer.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            assertThat(name).isIn("period", "paper", "real");
            assertThat(answer.get(name).isNumber()).isFalse();
        }
    }

    private static void assertNoForbiddenField(JsonNode node) {
        if (node.isObject()) {
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                String lower = name.toLowerCase(Locale.ROOT);
                for (String forbidden : FORBIDDEN) {
                    assertThat(lower).as("field %s", name).isNotEqualTo(forbidden);
                    assertThat(lower).as("field %s", name).doesNotStartWith(forbidden + "_");
                }
                assertThat(lower)
                        .doesNotContain("difference")
                        .doesNotContain("percent")
                        .doesNotContain("winner");
                assertNoForbiddenField(node.get(name));
            }
        } else if (node.isArray()) {
            node.forEach(PnlNoThirdNumberTest::assertNoForbiddenField);
        }
    }

    private static List<String> keys(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    // ----- random answers -----

    private static ModePnlDtos.PnlPeriodDto period(Random random) {
        if (random.nextBoolean()) {
            return new ModePnlDtos.PnlPeriodDto("ALL", null, null);
        }
        Instant from = Instant.ofEpochSecond(1_700_000_000L + random.nextInt(10_000_000));
        return new ModePnlDtos.PnlPeriodDto("RANGE", from, from.plusSeconds(1 + random.nextInt(31_000_000)));
    }

    private static BlockResult<ModePnlDtos.PaperModeBlockDto> paperResult(Random random) {
        if (random.nextInt(5) == 0) {
            return new BlockResult.Unavailable<>(
                    PnlMarket.PAPER, "USD", "SERVICE_UNAVAILABLE", "c-" + random.nextInt());
        }
        List<ModePnlDtos.PaperBotPnlLineDto> lines = new ArrayList<>();
        for (int i = random.nextInt(4); i > 0; i--) {
            lines.add(paperLine(random));
        }
        return new BlockResult.Available<>(new ModePnlDtos.PaperModeBlockDto(
                "AVAILABLE",
                "PAPER",
                "USD",
                "O resultado simulado não garante o resultado real.",
                lines.isEmpty(),
                usdAmount(random),
                usd(random),
                usdAmount(random),
                Instant.ofEpochSecond(1_800_000_000L),
                usd(random),
                lines));
    }

    private static BlockResult<ModePnlDtos.RealModeBlockDto> realResult(Random random) {
        if (random.nextInt(5) == 0) {
            return new BlockResult.Unavailable<>(
                    PnlMarket.REAL, "SOL", "RECONCILIATION_MISMATCH", "c-" + random.nextInt());
        }
        List<ModePnlDtos.RealBotPnlLineDto> lines = new ArrayList<>();
        for (int i = random.nextInt(4); i > 0; i--) {
            lines.add(realLine(random));
        }
        return new BlockResult.Available<>(new ModePnlDtos.RealModeBlockDto(
                "AVAILABLE",
                "REAL",
                "SOL",
                lines.isEmpty(),
                lamportsAmount(random),
                lamports(random),
                lamportsAmount(random),
                Instant.ofEpochSecond(1_800_000_000L),
                lamports(random),
                lines,
                new ModePnlDtos.UnownedPnlLineDto(
                        lamports(random), lamportsAmount(random), lamports(random), List.of("UNOWNED_EXCESS"))));
    }

    private static BlockResult<ModePnlDtos.PaperComparisonSideDto> paperSide(Random random) {
        if (random.nextInt(4) == 0) {
            return new BlockResult.Unavailable<>(
                    PnlMarket.PAPER, "USD", "SERVICE_UNAVAILABLE", "c-" + random.nextInt());
        }
        return new BlockResult.Available<>(new ModePnlDtos.PaperComparisonSideDto(
                "AVAILABLE",
                "PAPER",
                "USD",
                "O resultado simulado não garante o resultado real.",
                Instant.ofEpochSecond(1_700_000_000L),
                "MOMENTUM_VOLUME",
                paperLine(random)));
    }

    private static BlockResult<ModePnlDtos.RealComparisonSideDto> realSide(Random random) {
        if (random.nextInt(4) == 0) {
            return new BlockResult.Unavailable<>(PnlMarket.REAL, "SOL", "SERVICE_UNAVAILABLE", "c-" + random.nextInt());
        }
        return new BlockResult.Available<>(new ModePnlDtos.RealComparisonSideDto(
                "AVAILABLE",
                "REAL",
                "SOL",
                Instant.ofEpochSecond(1_700_000_000L),
                "MOMENTUM_VOLUME",
                realLine(random)));
    }

    private static ModePnlDtos.PaperBotPnlLineDto paperLine(Random random) {
        return new ModePnlDtos.PaperBotPnlLineDto(
                new UUID(random.nextLong(), random.nextLong()),
                "Bot " + random.nextInt(100),
                "ACTIVE",
                usdAmount(random),
                usd(random),
                usdAmount(random),
                usd(random));
    }

    private static ModePnlDtos.RealBotPnlLineDto realLine(Random random) {
        return new ModePnlDtos.RealBotPnlLineDto(
                new UUID(random.nextLong(), random.nextLong()),
                "Bot " + random.nextInt(100),
                "PAUSED",
                lamportsAmount(random),
                lamports(random),
                lamportsAmount(random),
                lamports(random),
                new ModePnlDtos.ProratedSharesDto(List.of(), 0, List.of()));
    }

    private static PnlAmountDto usdAmount(Random random) {
        return random.nextInt(6) == 0
                ? PnlAmountDto.of(new com.memetrade.pnl.domain.PnlCalculator.Amount.Unknown(
                        com.memetrade.pnl.domain.Mark.UnrealizedUnknownReason.PRICE_UNKNOWN))
                : PnlAmountDto.of(new com.memetrade.pnl.domain.PnlCalculator.Amount.Known(new BigDecimal(usd(random))));
    }

    private static String usd(Random random) {
        return BigDecimal.valueOf(random.nextLong() % 1_000_000_000L, 6).toPlainString();
    }

    private static ModePnlDtos.LamportsAmountDto lamportsAmount(Random random) {
        return random.nextInt(6) == 0
                ? new ModePnlDtos.LamportsUnknownDto("UNKNOWN", "PRICE_UNKNOWN")
                : new ModePnlDtos.LamportsKnownDto("KNOWN", lamports(random));
    }

    private static String lamports(Random random) {
        return Long.toString(random.nextLong() % 1_000_000_000_000L);
    }
}
