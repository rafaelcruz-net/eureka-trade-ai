package com.memetrade.riskassessment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.UnknownReason;
import com.memetrade.riskassessment.api.DecisionHint;
import com.memetrade.riskassessment.api.FactorId;
import com.memetrade.riskassessment.api.RiskBand;
import com.memetrade.riskassessment.api.UnavailableReason;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** BR3.1 to BR3.6 and BR3.8 with known-value tables of version 1 (written here from the rules). */
@Tag("u4")
class RiskCalculatorTest {

    private static final SourceRef SOURCE = new SourceRef("sim-a", "A", true);
    private static final Instant AT = Instant.parse("2026-10-28T12:00:00Z");
    private static final WeightSet V1 = WeightsV1.weights();

    @ParameterizedTest(name = "liquidity {0} -> risk {1}")
    @CsvSource({
        "0, 100",
        "1000, 100",
        "5500, 80",
        "1112.5, 100",
        "1113, 99",
        "10000, 60",
        "50000, 25",
        "75000, 13",
        "100000, 0",
        "250000, 0"
    })
    void liquidityCurvePointsAndInterpolationRoundHalfUp(String input, int risk) {
        assertThat(Curve.risk(V1.factor(FactorId.LIQUIDITY).orElseThrow().curve(), new BigDecimal(input)))
                .isEqualTo(risk);
    }

    @ParameterizedTest(name = "price change {0}% -> risk {1}")
    @CsvSource({"0, 0", "1.5, 5", "5, 15", "10, 30", "20, 50", "30, 70", "45, 85", "60, 100", "99.9999, 100"})
    void increasingCurveOfThePriceChange(String input, int risk) {
        assertThat(Curve.risk(V1.factor(FactorId.PRICE_CHANGE_1H).orElseThrow().curve(), new BigDecimal(input)))
                .isEqualTo(risk);
    }

    @Test
    void theIndicatorIsTheRoundedWeightedSumOfExactContributions() {
        RiskComputation result = RiskCalculator.calculate(V1, inputs("5500", "200", "10000", "10"), false);

        assertThat(result.score()).contains(61);
        assertThat(result.band()).contains(RiskBand.MEDIUM);
        assertThat(result.coverage()).isEqualByComparingTo("100.0000");
        assertThat(result.decisionHint()).isEqualTo(DecisionHint.ELIGIBLE);
        assertThat(result.factors())
                .extracting(FactorResult::contribution)
                .containsExactly(
                        Optional.of(new BigDecimal("28.0000")),
                        Optional.of(new BigDecimal("15.0000")),
                        Optional.of(new BigDecimal("12.0000")),
                        Optional.of(new BigDecimal("6.0000")));
    }

    @Test
    void coverageOfExactlyEightyIsEnoughAndJustBelowIsNoTrade() {
        RiskComputation eighty = RiskCalculator.calculate(V1, inputs("200000", "10000", "2000000", null), false);
        RiskComputation seventyFive = RiskCalculator.calculate(V1, inputs("200000", null, "2000000", "0"), false);

        assertThat(eighty.available()).isTrue();
        assertThat(eighty.coverage()).isEqualByComparingTo("80.0000");
        assertThat(seventyFive.available()).isFalse();
        assertThat(seventyFive.unavailableReason()).contains(UnavailableReason.INSUFFICIENT_COVERAGE);
        assertThat(seventyFive.decisionHint()).isEqualTo(DecisionHint.NO_TRADE);
        assertThat(seventyFive.score()).isEmpty();
        assertThat(seventyFive.factors()).hasSize(4);
    }

    @Test
    void justBelowTheMinimumCoverageInAnotherVersionIsUnavailable() {
        WeightSet strict = new WeightSet(2, V1.factors(), 39, 69, new BigDecimal("80.0001"));

        assertThat(RiskCalculator.calculate(strict, inputs("200000", "10000", "2000000", null), false)
                        .available())
                .isFalse();
    }

    @ParameterizedTest(name = "score {0} -> {1}")
    @CsvSource({"0, LOW", "39, LOW", "40, MEDIUM", "69, MEDIUM", "70, HIGH", "100, HIGH"})
    void bandsComeFromTheLimitsOfTheVersion(int score, RiskBand band) {
        assertThat(RiskCalculator.band(score, V1)).isEqualTo(band);
    }

    @Test
    void anUnknownFactorCountsAsRiskOneHundredAndRaisesLowToMedium() {
        RiskComputation result = RiskCalculator.calculate(V1, inputs("200000", "10000", "2000000", null), false);

        assertThat(result.score()).contains(20);
        assertThat(result.band()).contains(RiskBand.MEDIUM);
        assertThat(result.bandRaisedByUnknown()).isTrue();
        FactorResult unknown = result.factors().get(3);
        assertThat(unknown.factorRisk()).isEqualTo(100);
        assertThat(unknown.contribution()).isEmpty();
    }

    @Test
    void aStaleInputCountsAsKnown() {
        Map<FactorId, MarketValue> inputs = inputs("5500", "200", "10000", "10");
        inputs.put(FactorId.LIQUIDITY, new MarketValue.Known(new BigDecimal("5500"), SOURCE, AT, true));

        assertThat(RiskCalculator.calculate(V1, inputs, false).score()).contains(61);
    }

    @Test
    void withEverySourceFailedTheIndicatorIsUnavailableWithZeroCoverage() {
        RiskComputation result = RiskCalculator.calculate(V1, inputs("5500", "200", "10000", "10"), true);

        assertThat(result.unavailableReason()).contains(UnavailableReason.SOURCE_UNAVAILABLE);
        assertThat(result.coverage()).isEqualByComparingTo("0.0000");
        assertThat(result.factors()).allSatisfy(f -> {
            assertThat(f.input())
                    .isEqualTo(new MarketValue.Unknown(UnknownReason.SOURCE_UNAVAILABLE, Optional.empty()));
            assertThat(f.factorRisk()).isEqualTo(100);
        });
    }

    @Test
    void thePriceChangeIsAbsoluteInPointsAtScaleFourAndNeverDividesByZero() {
        MarketValue current = known("1");

        assertThat(((MarketValue.Known) PriceChange.of(known("1.10"), known("1.00"))).value())
                .isEqualByComparingTo("10.0000");
        assertThat(((MarketValue.Known) PriceChange.of(current, known("3"))).value())
                .isEqualTo(new BigDecimal("66.6667"));
        assertThat(PriceChange.of(current, known("0"))).isInstanceOf(MarketValue.Unknown.class);
        assertThat(PriceChange.of(current, new MarketValue.Unknown(UnknownReason.NOT_YET_OBSERVED, Optional.empty())))
                .isEqualTo(new MarketValue.Unknown(UnknownReason.NOT_YET_OBSERVED, Optional.empty()));
        MarketValue.Unknown noPrice = MarketValue.Unknown.notProvided(SOURCE);
        assertThat(PriceChange.of(noPrice, known("1"))).isEqualTo(noPrice);
    }

    @Test
    void theFingerprintIgnoresStaleButFollowsValuesSourcesTimesAndVersion() {
        Map<FactorId, MarketValue> inputs = inputs("5500", "200", "10000", "10");
        Map<FactorId, MarketValue> stale = new EnumMap<>(inputs);
        stale.put(FactorId.LIQUIDITY, new MarketValue.Known(new BigDecimal("5500"), SOURCE, AT, true));
        Map<FactorId, MarketValue> other = new EnumMap<>(inputs);
        other.put(FactorId.LIQUIDITY, known("5501"));

        String base = InputsFingerprint.of(1, inputs, List.of());
        assertThat(InputsFingerprint.of(1, stale, List.of())).isEqualTo(base);
        assertThat(InputsFingerprint.of(1, other, List.of())).isNotEqualTo(base);
        assertThat(InputsFingerprint.of(2, inputs, List.of())).isNotEqualTo(base);
        assertThat(InputsFingerprint.of(1, inputs, List.of(known("1")))).isNotEqualTo(base);
    }

    /** NFR4.1: 1 000 seeded combinations: 0 to 100, the same twice, never LOW with an unknown factor. */
    @Test
    void propertyEveryIndicatorIsBetweenZeroAndOneHundredDeterministicAndNeverLowWithUnknown() {
        List<RiskComputation> first = random(1_000);
        List<RiskComputation> second = random(1_000);

        assertThat(first).isEqualTo(second);
        for (RiskComputation result : first) {
            result.score().ifPresent(score -> assertThat(score).isBetween(0, 100));
            boolean anyUnknown = result.factors().stream().anyMatch(FactorResult::isUnknown);
            if (anyUnknown && result.available()) {
                assertThat(result.band()).isNotEqualTo(Optional.of(RiskBand.LOW));
            }
        }
        assertThat(first).anyMatch(RiskComputation::available).anyMatch(r -> !r.available());
    }

    private static List<RiskComputation> random(int count) {
        Random random = new Random(42);
        List<RiskComputation> results = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            results.add(RiskCalculator.calculate(
                    V1,
                    inputs(
                            maybe(random, BigDecimal.valueOf(random.nextInt(300_000), 2)),
                            maybe(random, BigDecimal.valueOf(random.nextInt(8_000))),
                            maybe(random, BigDecimal.valueOf(random.nextInt(2_000_000), 1)),
                            maybe(random, BigDecimal.valueOf(random.nextInt(1_000_000), 4))),
                    random.nextInt(50) == 0));
        }
        return results;
    }

    private static String maybe(Random random, BigDecimal value) {
        return random.nextInt(6) == 0 ? null : value.toPlainString();
    }

    private static Map<FactorId, MarketValue> inputs(String liquidity, String holders, String volume, String change) {
        Map<FactorId, MarketValue> inputs = new EnumMap<>(FactorId.class);
        inputs.put(FactorId.LIQUIDITY, valueOrUnknown(liquidity));
        inputs.put(FactorId.HOLDERS, valueOrUnknown(holders));
        inputs.put(FactorId.VOLUME_24H, valueOrUnknown(volume));
        inputs.put(FactorId.PRICE_CHANGE_1H, valueOrUnknown(change));
        return inputs;
    }

    private static MarketValue valueOrUnknown(String value) {
        return value == null ? MarketValue.Unknown.notProvided(SOURCE) : known(value);
    }

    private static MarketValue known(String value) {
        return new MarketValue.Known(new BigDecimal(value), SOURCE, AT, false);
    }
}
