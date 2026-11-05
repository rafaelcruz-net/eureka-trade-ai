package com.memetrade.riskcontrol.domain;

import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.UnknownReason;
import com.memetrade.pnl.api.PortfolioRiskView.RiskSnapshot;
import com.memetrade.riskcontrol.api.BotLimitsVersionRef;
import com.memetrade.riskcontrol.api.CandidateOrder;
import com.memetrade.riskcontrol.api.OrderSide;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The base of every case of the catalogue (functional-spec.md, "Catálogo de exemplos"), changed one value at a time.
 * Every value is written as text, copied from the specification; nothing is computed here.
 */
final class GateScenario {

    static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");
    static final SourceRef SOURCE = new SourceRef("sim-test", "Simulado de teste", true);

    OrderSide side = OrderSide.BUY;
    String quantity = "100.000000";
    MarketValue price = known("1.000000000000", Duration.ZERO);
    MarketValue liquidity = known("10000.000000", Duration.ZERO);
    String tokenExposure = "0.000000";
    String totalExposure = "0.000000";
    String dayLoss = "0.000000";
    Optional<String> equity = Optional.of("1000.000000");
    String peakEquity = "1000.000000";
    String balance = "1000.000000";
    Optional<Duration> lastTradeAgo = Optional.empty();
    String maxOrderSize = "100.000000";
    String maxTokenExposure = "200.000000";
    String maxTotalExposure = "500.000000";
    String maxSlippagePct = "2.0000";
    String minLiquidity = "10000.000000";
    long minIntervalSeconds = 300;
    String maxDailyLoss = "50.000000";
    String maxDrawdownPct = "10.0000";
    UUID decisionId = UUID.randomUUID();

    static GateScenario base() {
        return new GateScenario();
    }

    static MarketValue.Known known(String value, Duration age) {
        return new MarketValue.Known(new BigDecimal(value), SOURCE, NOW.minus(age), false);
    }

    static MarketValue.Unknown unknown() {
        return new MarketValue.Unknown(UnknownReason.SOURCE_UNAVAILABLE, Optional.of(SOURCE));
    }

    GateScenario sell(String qty) {
        side = OrderSide.SELL;
        quantity = qty;
        return this;
    }

    GateScenario buy(String qty) {
        side = OrderSide.BUY;
        quantity = qty;
        return this;
    }

    GateScenario exposures(String token, String total) {
        tokenExposure = token;
        totalExposure = total;
        return this;
    }

    CandidateOrder order() {
        return new CandidateOrder(
                UUID.randomUUID(), decisionId, side, "solana", "SyntheticMint1111", new BigDecimal(quantity));
    }

    BotLimitsVersionRef limits() {
        return new BotLimitsVersionRef(
                1,
                new BigDecimal(maxOrderSize),
                new BigDecimal(maxTokenExposure),
                new BigDecimal(maxTotalExposure),
                new BigDecimal(maxSlippagePct),
                new BigDecimal(minLiquidity),
                minIntervalSeconds,
                new BigDecimal(maxDailyLoss),
                new BigDecimal(maxDrawdownPct));
    }

    RiskSnapshot risk() {
        return new RiskSnapshot(
                new BigDecimal(tokenExposure),
                new BigDecimal(totalExposure),
                new BigDecimal(dayLoss),
                equity.map(BigDecimal::new),
                new BigDecimal(peakEquity),
                new BigDecimal(balance),
                lastTradeAgo.map(NOW::minus));
    }

    GateInputs inputs() {
        return new GateInputs(order(), limits(), price, liquidity, risk(), NOW);
    }
}
