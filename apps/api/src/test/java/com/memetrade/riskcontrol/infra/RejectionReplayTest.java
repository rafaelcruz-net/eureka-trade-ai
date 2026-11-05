package com.memetrade.riskcontrol.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.UnknownReason;
import com.memetrade.pnl.api.PortfolioRiskView.RiskSnapshot;
import com.memetrade.riskcontrol.api.BotLimitsVersionRef;
import com.memetrade.riskcontrol.api.CandidateOrder;
import com.memetrade.riskcontrol.api.OrderSide;
import com.memetrade.riskcontrol.domain.CheckOutcome;
import com.memetrade.riskcontrol.domain.GateInputs;
import com.memetrade.riskcontrol.domain.LimitChecks;
import com.memetrade.riskcontrol.domain.MarketInputs;
import com.memetrade.riskcontrol.domain.SlippageEstimate;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Passo 9 of U6, WRITTEN BEFORE THE CODE (NFR8.1, NFR8.2, BR3.5): a recorded rejection is reproducible. The market
 * inputs go through the stored jsonb format and back; evaluating again with them, the recorded risk view, the limits
 * version and the same instant gives the same reasons, details and pause triggers.
 */
@Tag("u6")
class RejectionReplayTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");
    private static final SourceRef SOURCE = new SourceRef("sim-test", "Simulado de teste", true);

    private final LimitChecks checks = new LimitChecks(SlippageEstimate.V1, Duration.ofSeconds(60));

    @Test
    void everyKindOfRecordedRejectionIsReproducedFromItsMarketInputs() {
        List<MarketValue[]> markets = List.of(
                new MarketValue[] {known("1.000000000000", 0), known("10000.000000", 0)},
                new MarketValue[] {known("1.000000000000", 61), known("10000.000000", 0)},
                new MarketValue[] {unknown(), known("10000.000000", 0)},
                new MarketValue[] {known("1.000000000000", 0), unknown()},
                new MarketValue[] {known("1.000000000000", 0), known("9999.999999", 61)},
                new MarketValue[] {known("0.000012345678", 10), known("123.456789", 10)});
        List<String> quantities = List.of("100.000001", "150.010000", "100.000000");
        for (MarketValue[] market : markets) {
            for (String quantity : quantities) {
                for (RiskSnapshot risk : List.of(risk("1000.000000", "0.000000"), risk("900.000000", "50.000000"))) {
                    assertReproduced(market[0], market[1], quantity, risk);
                }
            }
        }
    }

    private void assertReproduced(MarketValue price, MarketValue liquidity, String quantity, RiskSnapshot risk) {
        CandidateOrder order = new CandidateOrder(
                UUID.randomUUID(),
                UUID.randomUUID(),
                OrderSide.BUY,
                "solana",
                "SyntheticMint1111",
                new BigDecimal(quantity));
        CheckOutcome original = checks.evaluate(new GateInputs(order, limits(), price, liquidity, risk, NOW));
        if (original.approved()) {
            return;
        }
        MarketInputs recorded =
                new MarketInputs(price, liquidity, original.estimatedSlippagePct(), original.orderValue());

        MarketInputs stored = MarketInputsCodec.read(MarketInputsCodec.write(recorded));
        CheckOutcome replayed = checks.evaluate(
                new GateInputs(order, limits(), stored.referencePrice(), stored.liquidity(), risk, NOW));

        assertThat(stored).isEqualTo(recorded);
        assertThat(replayed.details()).isEqualTo(original.details());
        assertThat(replayed.pauseTriggers()).isEqualTo(original.pauseTriggers());
        assertThat(replayed.estimatedSlippagePct()).isEqualTo(recorded.estimatedSlippagePct());
        assertThat(replayed.orderValue()).isEqualTo(recorded.orderValue());
    }

    private static BotLimitsVersionRef limits() {
        return new BotLimitsVersionRef(
                1,
                new BigDecimal("100.000000"),
                new BigDecimal("200.000000"),
                new BigDecimal("500.000000"),
                new BigDecimal("2.0000"),
                new BigDecimal("10000.000000"),
                300,
                new BigDecimal("50.000000"),
                new BigDecimal("10.0000"));
    }

    private static RiskSnapshot risk(String equity, String dayLoss) {
        return new RiskSnapshot(
                new BigDecimal("0.000000"),
                new BigDecimal("0.000000"),
                new BigDecimal(dayLoss),
                Optional.of(new BigDecimal(equity)),
                new BigDecimal("1000.000000"),
                new BigDecimal("1000.000000"),
                Optional.of(NOW.minusSeconds(120)));
    }

    private static MarketValue known(String value, long ageSeconds) {
        return new MarketValue.Known(new BigDecimal(value), SOURCE, NOW.minusSeconds(ageSeconds), false);
    }

    private static MarketValue unknown() {
        return new MarketValue.Unknown(UnknownReason.NOT_YET_OBSERVED, Optional.of(SOURCE));
    }
}
