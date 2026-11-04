package com.memetrade.pnl.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.UnknownReason;
import com.memetrade.pnl.api.PortfolioRiskView.RiskSnapshot;
import com.memetrade.pnl.domain.Mark.UnrealizedUnknownReason;
import com.memetrade.pnl.domain.PnlCalculator.Amount;
import com.memetrade.pnl.domain.PnlCalculator.TokenInput;
import com.memetrade.pnl.domain.RiskSnapshotCalculator.DayFill;
import com.memetrade.pnl.domain.RiskSnapshotCalculator.Exposure;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Passo 7 (written before the code): exit price and unrealized (C9), unknown and partial (C8), "no liquidity" (BR6.4),
 * exact sum of the lines (BR6.5), empty period (BR6.6), day loss (C2, BR7.2), exposure by cost (BR7.1) and the peak
 * (C9, BR7.3), every number by exact equality.
 */
@Tag("u7")
class PnlDomainTest {

    static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");
    static final SourceRef SOURCE = new SourceRef("sim-u7", "Simulado U7", true);

    private final ExitValuation valuation = new ExitValuation(new BigDecimal("0.5000"), new BigDecimal("0.3000"));

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private static MarketValue.Known known(String value) {
        return new MarketValue.Known(d(value), SOURCE, NOW, false);
    }

    private Mark c9Mark() {
        return valuation.value(d("100"), d("100.600000"), known("1.200000"), known("100000.000000"), NOW);
    }

    @Test
    void c9TheExitPriceAndTheUnrealizedOfAnOpenPosition() {
        Mark.Valued mark = (Mark.Valued) c9Mark();

        assertThat(mark.consumedPct()).isEqualTo(d("0.1200"));
        assertThat(mark.exitPrice()).isEqualTo(d("1.192560000000"));
        assertThat(mark.grossExitValue()).isEqualTo(d("119.256000"));
        assertThat(mark.estimatedFee()).isEqualTo(d("0.357768"));
        assertThat(mark.netExitValue()).isEqualTo(d("118.898232"));
        assertThat(mark.unrealized()).isEqualTo(d("18.298232"));
    }

    @Test
    void c9TheEquityIsTheBalancePlusTheNetExitValueAndRaisesThePeak() {
        Optional<BigDecimal> equity = EquityCalculator.equity(d("899.098200"), List.of(c9Mark()));

        assertThat(equity).contains(d("1017.996432"));
        assertThat(EquityCalculator.nextPeak(d("1000.000000"), equity)).isEqualTo(d("1017.996432"));
        assertThat(EquityCalculator.nextPeak(d("1100.000000"), equity))
                .as("the peak never goes down")
                .isEqualTo(d("1100.000000"));
    }

    @Test
    void c8AnUnknownPriceMakesTheLineAndTheTotalUnknownAndPartialAndKeepsThePeak() {
        Mark unknown = valuation.value(
                d("100"),
                d("100.600000"),
                new MarketValue.Unknown(UnknownReason.SOURCE_UNAVAILABLE, Optional.of(SOURCE)),
                known("100000"),
                NOW);
        assertThat(unknown).isEqualTo(new Mark.Unvalued(UnrealizedUnknownReason.PRICE_UNKNOWN));

        PnlCalculator.Report report = PnlCalculator.report(
                List.of(new TokenInput("solana", "A", d("0.000000"), d("0.301800"), Optional.of(unknown))));

        assertThat(report.lines().getFirst().unrealized())
                .isEqualTo(new Amount.Unknown(UnrealizedUnknownReason.PRICE_UNKNOWN));
        assertThat(report.unrealized()).isInstanceOf(Amount.Unknown.class);
        assertThat(report.partial()).isTrue();
        assertThat(report.realized()).isEqualTo(d("0.000000"));
        assertThat(report.fees()).isEqualTo(d("0.301800"));
        assertThat(EquityCalculator.equity(d("899.098200"), List.of(unknown))).isEmpty();
        assertThat(EquityCalculator.nextPeak(d("1000.000000"), Optional.empty()))
                .isEqualTo(d("1000.000000"));
    }

    @Test
    void aStalePriceIsStaleAndAZeroLiquidityIsNoLiquidityNeverZero() {
        MarketValue.Known old = new MarketValue.Known(d("1.2"), SOURCE, NOW.minus(Duration.ofSeconds(301)), false);
        assertThat(valuation.value(d("100"), d("100.6"), old, known("100000"), NOW))
                .isEqualTo(new Mark.Unvalued(UnrealizedUnknownReason.STALE));
        assertThat(valuation.value(d("100"), d("100.6"), known("1.2"), known("0"), NOW))
                .isEqualTo(new Mark.Unvalued(UnrealizedUnknownReason.NO_LIQUIDITY));

        PnlCalculator.Report report = PnlCalculator.report(List.of(new TokenInput(
                "solana",
                "A",
                d("0.000000"),
                d("0.000000"),
                Optional.of(new Mark.Unvalued(UnrealizedUnknownReason.NO_LIQUIDITY)))));
        assertThat(report.lines().getFirst().noLiquidity()).isTrue();
        assertThat(report.partial()).isTrue();
    }

    @Test
    void aPositionAboveTheLiquidityUsesTheWholeConsumptionCappedAtOneHundred() {
        Mark.Valued mark = (Mark.Valued) valuation.value(d("1000"), d("1000.000000"), known("1"), known("500"), NOW);

        assertThat(mark.consumedPct()).isEqualTo(d("100.0000"));
        assertThat(mark.exitPrice()).as("never below zero").isEqualTo(d("0.000000000000"));
        assertThat(mark.unrealized()).isEqualTo(d("-1000.000000"));
    }

    @Test
    void theLinesSumExactlyToTheTotalWithThreeTokens() {
        Mark a = valuation.value(d("100"), d("100.600000"), known("1.200000"), known("100000"), NOW);
        Mark b = valuation.value(d("33.333333"), d("10.000001"), known("0.333333"), known("12345.678901"), NOW);
        PnlCalculator.Report report = PnlCalculator.report(List.of(
                new TokenInput("solana", "A", d("48.425000"), d("0.748875"), Optional.of(a)),
                new TokenInput("solana", "B", d("-21.064000"), d("0.540408"), Optional.of(b)),
                new TokenInput("solana", "C", d("0.000001"), d("0.000003"), Optional.empty())));

        BigDecimal realized = report.lines().stream().map(l -> l.realized()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fees = report.lines().stream().map(l -> l.fees()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal unrealized = report.lines().stream()
                .map(l -> ((Amount.Known) l.unrealized()).value())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(report.realized()).isEqualTo(realized).isEqualTo(d("27.361001"));
        assertThat(report.fees()).isEqualTo(fees).isEqualTo(d("1.289286"));
        assertThat(((Amount.Known) report.unrealized()).value()).isEqualTo(unrealized);
        assertThat(report.lines().get(2).unrealized()).isEqualTo(new Amount.Known(d("0.000000")));
        assertThat(report.partial()).isFalse();
        assertThat(report.empty()).isFalse();
    }

    @Test
    void anEmptyPeriodHasZeroTotalsAndIsMarkedEmpty() {
        PnlCalculator.Report report = PnlCalculator.report(List.of());

        assertThat(report.empty()).isTrue();
        assertThat(report.realized()).isEqualTo(d("0.000000"));
        assertThat(report.fees()).isEqualTo(d("0.000000"));
        assertThat(report.unrealized()).isEqualTo(new Amount.Known(d("0.000000")));
        assertThat(report.partial()).isFalse();
        assertThat(report.lines()).isEmpty();
    }

    @Test
    void c2TheDayLossCountsRealizedAndFeesOfTheDay() {
        RiskSnapshot snapshot = RiskSnapshotCalculator.snapshot(
                "solana",
                "A",
                List.of(),
                List.of(new DayFill(d("0.000000"), d("0.301800")), new DayFill(d("-21.064000"), d("0.238608"))),
                Optional.of(d("978.395592")),
                d("1000.000000"),
                d("978.395592"),
                Optional.of(NOW));

        assertThat(snapshot.realizedDayLossWithFees()).isEqualTo(d("21.604408"));
        assertThat(snapshot.availableVirtualBalance()).isEqualTo(d("978.395592"));
        assertThat(snapshot.lastTradeAt()).contains(NOW);
    }

    @Test
    void aDayWithAGainHasNoLossAndTheExposureIsTheCost() {
        RiskSnapshot snapshot = RiskSnapshotCalculator.snapshot(
                "solana",
                "B",
                List.of(new Exposure("solana", "A", d("100.600000")), new Exposure("solana", "B", d("226.500000"))),
                List.of(new DayFill(d("73.525000"), d("0.447075"))),
                Optional.empty(),
                d("1000.000000"),
                d("500.000000"),
                Optional.empty());

        assertThat(snapshot.realizedDayLossWithFees()).isEqualTo(d("0.000000"));
        assertThat(snapshot.tokenExposure()).isEqualTo(d("226.500000"));
        assertThat(snapshot.totalExposure()).isEqualTo(d("327.100000"));
        assertThat(snapshot.equity()).as("unknown equity is empty, never zero").isEmpty();
        assertThat(snapshot.peakEquity()).isEqualTo(d("1000.000000"));
    }

    @Test
    void aTokenWithoutPositionHasZeroExposure() {
        RiskSnapshot snapshot = RiskSnapshotCalculator.snapshot(
                "solana",
                "Z",
                List.of(new Exposure("solana", "A", d("1.000000"))),
                List.of(),
                Optional.of(d("1.000000")),
                d("1.000000"),
                d("0.000000"),
                Optional.empty());

        assertThat(snapshot.tokenExposure()).isEqualTo(d("0.000000"));
        assertThat(snapshot.realizedDayLossWithFees()).isEqualTo(d("0.000000"));
    }
}
