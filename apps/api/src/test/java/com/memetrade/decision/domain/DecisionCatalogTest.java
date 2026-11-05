package com.memetrade.decision.domain;

import static com.memetrade.decision.domain.DecisionScenarios.TOKEN_A;
import static com.memetrade.decision.domain.DecisionScenarios.TOKEN_B;
import static com.memetrade.decision.domain.DecisionScenarios.TOKEN_C;
import static com.memetrade.decision.domain.DecisionScenarios.append;
import static com.memetrade.decision.domain.DecisionScenarios.repeat;
import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.marketdata.api.PriceVolumeWindow;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.riskassessment.api.AssessmentResult;
import com.memetrade.riskassessment.api.RiskBand;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Catalogue D1 to D18 of functional-spec.md, written BEFORE {@link DecisionEngine} (Passo 7; US4.5; BR4.1 to BR4.10;
 * NFR4.1, NFR4.6, NFR8.1). Every expected value is the text of the catalogue, compared by exact equality; a missing
 * value is a NO_TRADE reason, never a zero.
 */
@Tag("u5")
class DecisionCatalogTest {

    private final DecisionEngine engine = new DecisionEngine();

    /** D1: window 15 min; vol 1.000.000 to 1.200.000, price 1,00 to 1,02: BUY with 20,0000%, 2,0000%, strength 1. */
    @Test
    void d1MomentumAtTheThresholdBuys() {
        DecisionOutcome outcome = engine.decide(DecisionScenarios.momentum()
                .token(TOKEN_A, prices("1.00", "1.01", "1.01", "1.02"), volumes("1000000", "1200000"), "1.02")
                .build());

        assertThat(outcome.action()).isEqualTo(DecisionAction.BUY);
        assertThat(outcome.token()).contains(TOKEN_A);
        assertThat(value(outcome, TOKEN_A, "volumeGrowthPct")).isEqualTo(new BigDecimal("20.0000"));
        assertThat(value(outcome, TOKEN_A, "priceRisePct")).isEqualTo(new BigDecimal("2.0000"));
        assertThat(value(outcome, TOKEN_A, "signalStrength")).isEqualByComparingTo("1");
        assertThat(outcome.summary()).startsWith("Comprar");
        assertThat(outcome.validUntil()).isEqualTo(DecisionScenarios.SLOT.plus(Duration.ofMinutes(5)));
    }

    /** D2: vol(b_n) 1.199.999 gives 19,9999%: no signal. */
    @Test
    void d2MomentumBelowTheThresholdHasNoSignal() {
        DecisionOutcome outcome = engine.decide(DecisionScenarios.momentum()
                .token(TOKEN_A, prices("1.00", "1.01", "1.01", "1.02"), volumes("1000000", "1199999"), "1.02")
                .build());

        assertThat(outcome.action()).isEqualTo(DecisionAction.NO_TRADE);
        assertThat(outcome.noTradeReason()).contains(NoTradeReason.NO_SIGNAL);
        assertThat(value(outcome, TOKEN_A, "volumeGrowthPct")).isEqualTo(new BigDecimal("19.9999"));
        assertThat(factor(outcome, TOKEN_A, "volumeGrowthPct").satisfied()).isFalse();
    }

    /** D3: one price of the window UNKNOWN gives NO_TRADE UNKNOWN_DATA, the factor recorded as UNKNOWN. */
    @Test
    void d3AnUnknownBucketGivesUnknownData() {
        DecisionOutcome outcome = engine.decide(DecisionScenarios.momentum()
                .token(TOKEN_A, prices("1.00", null, "1.01", "1.02"), volumes("1000000", "1200000"), "1.02")
                .build());

        assertThat(outcome.action()).isEqualTo(DecisionAction.NO_TRADE);
        assertThat(outcome.noTradeReason()).contains(NoTradeReason.UNKNOWN_DATA);
        assertThat(factor(outcome, TOKEN_A, "priceRisePct").value()).isEqualTo(FactorValue.unknown());
    }

    /** D4: maximum 1,00 over 24 buckets and price(b_n) 1,010000000000 with the 1% margin: BUY. */
    @Test
    void d4BreakoutAtTheThresholdBuys() {
        DecisionOutcome outcome = engine.decide(DecisionScenarios.range()
                .token(TOKEN_A, append(repeat("1.00", 24), "1.010000000000"), repeat("500000", 25), "1.010000000000")
                .build());

        assertThat(outcome.action()).isEqualTo(DecisionAction.BUY);
        assertThat(value(outcome, TOKEN_A, "breakoutThreshold")).isEqualByComparingTo("1.01");
    }

    /** D5: price(b_n) 1,009999999999: no signal. */
    @Test
    void d5BreakoutBelowTheThresholdHasNoSignal() {
        DecisionOutcome outcome = engine.decide(DecisionScenarios.range()
                .token(TOKEN_A, append(repeat("1.00", 24), "1.009999999999"), repeat("500000", 25), "1.009999999999")
                .build());

        assertThat(outcome.action()).isEqualTo(DecisionAction.NO_TRADE);
        assertThat(outcome.noTradeReason()).contains(NoTradeReason.NO_SIGNAL);
    }

    /** D6: average price 1,00 and reference 1,10: SELL TAKE_PROFIT of the whole position. */
    @Test
    void d6TakeProfitSellsTheWholePosition() {
        DecisionOutcome outcome = engine.decide(withPosition("1.10", 10));

        assertThat(outcome.action()).isEqualTo(DecisionAction.SELL);
        assertThat(outcome.exitReason()).contains(ExitReason.TAKE_PROFIT);
        assertThat(outcome.requestedQuantity()).contains(new BigDecimal("100.000000"));
        assertThat(value(outcome, TOKEN_A, "pnlPct")).isEqualTo(new BigDecimal("10.0000"));
        assertThat(outcome.summary()).startsWith("Vender");
    }

    /** D7: reference 0,95: SELL STOP_LOSS. */
    @Test
    void d7StopLossSells() {
        DecisionOutcome outcome = engine.decide(withPosition("0.95", 10));

        assertThat(outcome.action()).isEqualTo(DecisionAction.SELL);
        assertThat(outcome.exitReason()).contains(ExitReason.STOP_LOSS);
        assertThat(value(outcome, TOKEN_A, "pnlPct")).isEqualTo(new BigDecimal("-5.0000"));
    }

    /** D8: open for 240 minutes: SELL MAX_HOLD; for 239 minutes: HOLD. */
    @Test
    void d8TheMaximumTimeSellsAtTwoHundredFortyMinutesAndHoldsAtTwoHundredThirtyNine() {
        DecisionOutcome at240 = engine.decide(withPosition("1.00", 240));
        DecisionOutcome at239 = engine.decide(withPosition("1.00", 239));

        assertThat(at240.action()).isEqualTo(DecisionAction.SELL);
        assertThat(at240.exitReason()).contains(ExitReason.MAX_HOLD);
        assertThat(at239.action()).isEqualTo(DecisionAction.HOLD);
        assertThat(at239.token()).contains(TOKEN_A);
        assertThat(at239.summary()).startsWith("Manter");
    }

    /** D9: breakout with price(b_n) below the minimum of the range: SELL RANGE_EXIT. */
    @Test
    void d9TheBreakoutSellsWhenThePriceLeavesTheRangeFromBelow() {
        DecisionOutcome outcome = engine.decide(DecisionScenarios.range()
                .token(TOKEN_A, append(repeat("1.00", 24), "0.98"), repeat("500000", 25), "0.98")
                .position(TOKEN_A, "100.000000", "100.000000", 30)
                .build());

        assertThat(outcome.action()).isEqualTo(DecisionAction.SELL);
        assertThat(outcome.exitReason()).contains(ExitReason.RANGE_EXIT);
    }

    /** D10: balance 1.000, fraction 10%, maximum per order 100, price 0,5: quantity 200,000000. */
    @Test
    void d10TheBuyUsesAFractionOfTheBalance() {
        assertThat(OrderSizer.quantity(
                        new BigDecimal("1000"), new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("0.5"), 6))
                .isEqualTo(new BigDecimal("200.000000"));
    }

    /** D11: balance 5.000, fraction 10%, maximum per order 100, price 0,5: value 100, quantity 200,000000. */
    @Test
    void d11TheBuyIsCappedByTheMaximumPerOrder() {
        assertThat(OrderSizer.quantity(
                        new BigDecimal("5000"), new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("0.5"), 6))
                .isEqualTo(new BigDecimal("200.000000"));
    }

    /** D12: balance 0,000001: the quantity truncates to zero, NO_TRADE QUANTITY_BELOW_MINIMUM. */
    @Test
    void d12AZeroQuantityIsNoTrade() {
        assertThat(OrderSizer.quantity(
                                new BigDecimal("0.000001"),
                                new BigDecimal("10"),
                                new BigDecimal("100"),
                                new BigDecimal("0.5"),
                                6)
                        .signum())
                .isZero();
        DecisionOutcome outcome = engine.decide(DecisionScenarios.momentum()
                .balance("0.000001")
                .token(TOKEN_A, prices("1.00", "1.01", "1.01", "1.02"), volumes("1000000", "1200000"), "1.02")
                .build());

        assertThat(outcome.action()).isEqualTo(DecisionAction.NO_TRADE);
        assertThat(outcome.noTradeReason()).contains(NoTradeReason.QUANTITY_BELOW_MINIMUM);
    }

    /** D13: a BUY signal with the band HIGH: NO_TRADE RISK_BAND_HIGH, the band among the factors. */
    @Test
    void d13AHighRiskBandBlocksTheBuy() {
        DecisionOutcome outcome = engine.decide(DecisionScenarios.momentum()
                .tokenWithBand(
                        TOKEN_A,
                        prices("1.00", "1.01", "1.01", "1.02"),
                        volumes("1000000", "1200000"),
                        "1.02",
                        RiskBand.HIGH)
                .build());

        assertThat(outcome.action()).isEqualTo(DecisionAction.NO_TRADE);
        assertThat(outcome.noTradeReason()).contains(NoTradeReason.RISK_BAND_HIGH);
        assertThat(factor(outcome, TOKEN_A, "riskBand").label()).contains("HIGH");
        assertThat(factor(outcome, TOKEN_A, "riskBand").satisfied()).isFalse();
    }

    /** D14: token A with an exit and token B with a BUY signal: the SELL of A comes first. */
    @Test
    void d14AnExitComesBeforeABuy() {
        DecisionOutcome outcome = engine.decide(DecisionScenarios.momentum()
                .token(TOKEN_A, prices("1.00", "1.00", "1.00", "1.10"), volumes("1000000", "1000000"), "1.10")
                .token(TOKEN_B, prices("1.00", "1.01", "1.01", "1.02"), volumes("1000000", "1200000"), "1.02")
                .position(TOKEN_A, "100.000000", "100.000000", 10)
                .build());

        assertThat(outcome.action()).isEqualTo(DecisionAction.SELL);
        assertThat(outcome.token()).contains(TOKEN_A);
        assertThat(outcome.factors()).extracting(Factor::token).contains(TOKEN_A, TOKEN_B);
    }

    /** D15: B with strength 1,2 and C with strength 1,5: the BUY of C. */
    @Test
    void d15TheStrongestBuyWins() {
        DecisionOutcome outcome = engine.decide(DecisionScenarios.momentum()
                .token(TOKEN_B, prices("1.00", "1.01", "1.02", "1.024"), volumes("1000000", "1240000"), "1.024")
                .token(TOKEN_C, prices("1.00", "1.01", "1.02", "1.03"), volumes("1000000", "1300000"), "1.03")
                .build());

        assertThat(outcome.action()).isEqualTo(DecisionAction.BUY);
        assertThat(outcome.token()).contains(TOKEN_C);
        assertThat(value(outcome, TOKEN_B, "signalStrength")).isEqualByComparingTo("1.2");
        assertThat(value(outcome, TOKEN_C, "signalStrength")).isEqualByComparingTo("1.5");
    }

    /** D16: no signal with an open position: HOLD. */
    @Test
    void d16NoSignalWithAPositionHolds() {
        DecisionOutcome outcome = engine.decide(withPosition("1.02", 10));

        assertThat(outcome.action()).isEqualTo(DecisionAction.HOLD);
    }

    /** D17: empty list and filters without result: NO_TRADE NO_TOKENS, without a token. */
    @Test
    void d17NoTokenIsNoTrade() {
        DecisionOutcome outcome = engine.decide(DecisionScenarios.momentum().build());

        assertThat(outcome.action()).isEqualTo(DecisionAction.NO_TRADE);
        assertThat(outcome.noTradeReason()).contains(NoTradeReason.NO_TOKENS);
        assertThat(outcome.token()).isEmpty();
        assertThat(outcome.summary()).startsWith("Não operar");
    }

    /** D18: the same input twice gives identical decisions (BR4.2). */
    @Test
    void d18TheDecisionIsDeterministic() {
        DecisionRequest request = DecisionScenarios.momentum()
                .token(TOKEN_B, prices("1.00", "1.01", "1.02", "1.024"), volumes("1000000", "1240000"), "1.024")
                .token(TOKEN_C, prices("1.00", "1.01", "1.02", "1.03"), volumes("1000000", "1300000"), "1.03")
                .build();

        assertThat(engine.decide(request)).isEqualTo(engine.decide(request));
    }

    // ----- BR4.1 per field (NFR4.6): unknown, stale, insufficient or unavailable is a reason, never a zero -----

    @Test
    void aWindowShorterThanTheStrategyNeedsIsInsufficientHistory() {
        DecisionOutcome outcome = engine.decide(DecisionScenarios.momentum()
                .token(TOKEN_A, prices("1.01", "1.01", "1.02"), volumes("1000000", "1000000", "1200000"), "1.02")
                .build());

        assertThat(outcome.noTradeReason()).contains(NoTradeReason.INSUFFICIENT_HISTORY);
    }

    @Test
    void aLastBucketOlderThanFiveMinutesIsStaleData() {
        PriceVolumeWindow window = DecisionScenarios.window(
                TOKEN_A, prices("1.00", "1.01", "1.01", "1.02"), volumes("1000000", "1200000"));
        PriceVolumeWindow.Point last = window.points().getLast();
        PriceVolumeWindow.Point old = new PriceVolumeWindow.Point(
                last.bucketStart(),
                DecisionScenarios.known(
                        "1.02",
                        DecisionScenarios.NOW.minus(Duration.ofMinutes(5).plusSeconds(1))),
                last.volume24h());
        List<PriceVolumeWindow.Point> points = new java.util.ArrayList<>(window.points());
        points.set(points.size() - 1, old);
        DecisionOutcome outcome = engine.decide(DecisionScenarios.momentum()
                .token(
                        TOKEN_A,
                        new PriceVolumeWindow(TOKEN_A, window.from(), window.to(), points),
                        DecisionScenarios.known("1.02"),
                        new AssessmentResult.Available(20, RiskBand.LOW, 1))
                .build());

        assertThat(outcome.noTradeReason()).contains(NoTradeReason.STALE_DATA);
    }

    @Test
    void anUnavailableAssessmentGivesAssessmentUnavailable() {
        DecisionOutcome outcome = engine.decide(DecisionScenarios.momentum()
                .tokenWithoutAssessment(
                        TOKEN_A, prices("1.00", "1.01", "1.01", "1.02"), volumes("1000000", "1200000"), "1.02")
                .build());

        assertThat(outcome.noTradeReason()).contains(NoTradeReason.ASSESSMENT_UNAVAILABLE);
    }

    @Test
    void aZeroBaseVolumeIsUnknownDataNeverADivisionByZero() {
        DecisionOutcome outcome = engine.decide(DecisionScenarios.momentum()
                .token(TOKEN_A, prices("1.00", "1.01", "1.01", "1.02"), volumes("0", "1200000"), "1.02")
                .build());

        assertThat(outcome.noTradeReason()).contains(NoTradeReason.UNKNOWN_DATA);
    }

    /** BR4.5: without a fresh reference price no exit is evaluated; the position stays (HOLD). */
    @Test
    void aPositionWithoutAFreshReferencePriceIsNeitherSoldNorZeroed() {
        DecisionOutcome outcome = engine.decide(DecisionScenarios.momentum()
                .token(
                        TOKEN_A,
                        DecisionScenarios.window(
                                TOKEN_A, prices("1.00", "1.00", "1.00", "1.00"), volumes("1000000", "1000000")),
                        DecisionScenarios.unknown(),
                        new AssessmentResult.Available(20, RiskBand.LOW, 1))
                .position(TOKEN_A, "100.000000", "100.000000", 600)
                .build());

        assertThat(outcome.action()).isEqualTo(DecisionAction.HOLD);
        assertThat(factor(outcome, TOKEN_A, "pnlPct").value()).isEqualTo(FactorValue.unknown());
    }

    /** BR4.8: the NO_TRADE reason is the one of the first token in list order that has a reason. */
    @Test
    void theNoTradeReasonIsTheOneOfTheFirstTokenWithAReason() {
        DecisionOutcome outcome = engine.decide(DecisionScenarios.momentum()
                .token(TOKEN_B, prices("1.00", "1.00", "1.00", "1.00"), volumes("1000000", "1000000"), "1.00")
                .token(TOKEN_A, prices("1.00", null, "1.00", "1.00"), volumes("1000000", "1000000"), "1.00")
                .build());

        assertThat(outcome.noTradeReason()).contains(NoTradeReason.UNKNOWN_DATA);
        assertThat(outcome.token()).contains(TOKEN_A);
    }

    /** BR4.8: equal strengths are broken by the mint in alphabetical order. */
    @Test
    void equalStrengthsAreBrokenByTheMintInAlphabeticalOrder() {
        DecisionOutcome outcome = engine.decide(DecisionScenarios.momentum()
                .token(TOKEN_C, prices("1.00", "1.01", "1.01", "1.02"), volumes("1000000", "1200000"), "1.02")
                .token(TOKEN_A, prices("1.00", "1.01", "1.01", "1.02"), volumes("1000000", "1200000"), "1.02")
                .build());

        assertThat(outcome.token()).contains(TOKEN_A);
    }

    /** BR4.6: the BUY quantity truncates DOWN to the places of the mint. */
    @Test
    void theBuyQuantityTruncatesToThePlacesOfTheMint() {
        DecisionOutcome outcome = engine.decide(DecisionScenarios.momentum()
                .token(TOKEN_A, prices("1.00", "1.01", "1.01", "1.02"), volumes("1000000", "1200000"), "1.02")
                .build());

        assertThat(outcome.requestedQuantity()).contains(new BigDecimal("98.039215"));
    }

    // ----- helpers -----

    /** A momentum token without signal (flat volume) with a position of 100 at cost 100, opened minutes ago. */
    private static DecisionRequest withPosition(String referencePrice, long minutesAgo) {
        return DecisionScenarios.momentum()
                .token(
                        TOKEN_A,
                        prices("1.00", "1.00", "1.00", referencePrice),
                        volumes("1000000", "1000000"),
                        referencePrice)
                .position(TOKEN_A, "100.000000", "100.000000", minutesAgo)
                .build();
    }

    private static String[] prices(String... prices) {
        return prices;
    }

    /** Volumes of a 4-bucket window: first and last given, the two middle ones equal to the first. */
    private static String[] volumes(String... volumes) {
        if (volumes.length == 2) {
            return new String[] {volumes[0], volumes[0], volumes[0], volumes[1]};
        }
        return volumes;
    }

    private static Factor factor(DecisionOutcome outcome, TokenId token, String name) {
        return outcome.factors().stream()
                .filter(f -> f.token().equals(token) && f.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No factor " + name + " for " + token.mint()));
    }

    private static BigDecimal value(DecisionOutcome outcome, TokenId token, String name) {
        FactorValue value = factor(outcome, token, name).value();
        if (value instanceof FactorValue.Known known) {
            return known.value();
        }
        throw new AssertionError("Factor " + name + " is UNKNOWN");
    }
}
