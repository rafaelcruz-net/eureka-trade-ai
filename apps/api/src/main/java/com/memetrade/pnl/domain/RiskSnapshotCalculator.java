package com.memetrade.pnl.domain;

import com.memetrade.pnl.api.PortfolioRiskView.RiskSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The risk view of C13 from facts already read (BR7.1, BR7.2, BR7.4). Pure. Exposure is the cost basis (always known);
 * the day loss is {@code max(0, -(realized - fees))} of the fills of the UTC day.
 */
public final class RiskSnapshotCalculator {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(6);

    private RiskSnapshotCalculator() {}

    /** The cost basis of one position of the bot. */
    public record Exposure(String chain, String mint, BigDecimal costBasis) {}

    /** Realized and fees (net of compensations) of one fill of the UTC day. */
    public record DayFill(BigDecimal realized, BigDecimal fees) {}

    public static RiskSnapshot snapshot(
            String chain,
            String mint,
            List<Exposure> positions,
            List<DayFill> dayFills,
            Optional<BigDecimal> equity,
            BigDecimal peakEquity,
            BigDecimal balance,
            Optional<Instant> lastTradeAt) {
        BigDecimal token = positions.stream()
                .filter(p -> p.chain().equals(chain) && p.mint().equals(mint))
                .map(Exposure::costBasis)
                .reduce(ZERO, BigDecimal::add);
        BigDecimal total = positions.stream().map(Exposure::costBasis).reduce(ZERO, BigDecimal::add);
        BigDecimal result = dayFills.stream()
                .map(fill -> fill.realized().subtract(fill.fees()))
                .reduce(ZERO, BigDecimal::add);
        BigDecimal dayLoss = result.signum() < 0 ? result.negate() : ZERO;
        return new RiskSnapshot(token, total, dayLoss, equity, peakEquity, balance, lastTradeAt);
    }
}
