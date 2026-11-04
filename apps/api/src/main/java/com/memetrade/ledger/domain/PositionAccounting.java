package com.memetrade.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Weighted average cost kept as TOTAL cost, not as an average price, so rounding never accumulates (BR2.3). A zeroed
 * position restarts the cost (BR2.4); a close realizes against the whole cost (BR5.1). The fee never enters the cost
 * (BR1.6). Pure.
 */
public final class PositionAccounting {

    private PositionAccounting() {}

    /** Quantity and total cost basis of one bot and token; zero quantity if and only if zero cost. */
    public record Holding(BigDecimal quantity, BigDecimal costBasis) {
        public static Holding empty() {
            return new Holding(BigDecimal.ZERO, Scales.zeroMoney());
        }

        public boolean isEmpty() {
            return quantity.signum() == 0;
        }
    }

    /** The holding after the fill, the cost removed and, for a sell or a close, the realized result. */
    public record Outcome(Holding after, BigDecimal costRemoved, Optional<BigDecimal> realized) {}

    /** BR2.3: {@code quantity += filled; costBasis += gross} (the fee stays out). */
    public static Outcome buy(Holding before, BigDecimal filled, BigDecimal gross) {
        requirePositive(filled);
        Holding after = new Holding(
                before.quantity().add(filled), Scales.money(before.costBasis().add(gross)));
        return new Outcome(after, Scales.zeroMoney(), Optional.empty());
    }

    /**
     * BR2.3: the whole cost leaves with the whole position; otherwise {@code money(costBasis x filled / quantity)}.
     * Realized = gross received - cost removed.
     */
    public static Outcome sell(Holding before, BigDecimal filled, BigDecimal gross) {
        requirePositive(filled);
        int comparison = filled.compareTo(before.quantity());
        if (comparison > 0) {
            throw new IllegalArgumentException("A sell never exceeds the position (BR1.5)");
        }
        BigDecimal removed = comparison == 0
                ? before.costBasis()
                : keepSmallestUnit(
                        before.costBasis(),
                        before.costBasis()
                                .multiply(filled)
                                .divide(before.quantity(), Scales.MONEY, RoundingMode.HALF_EVEN));
        BigDecimal quantity = before.quantity().subtract(filled);
        Holding after = quantity.signum() == 0
                ? Holding.empty()
                : new Holding(quantity, Scales.money(before.costBasis().subtract(removed)));
        return new Outcome(after, removed, Optional.of(Scales.money(gross.subtract(removed))));
    }

    /** BR5.1: the whole position at the reference value; realized = value - costBasis; the holding is zero. */
    public static Outcome close(Holding before, BigDecimal value) {
        return new Outcome(
                Holding.empty(), before.costBasis(), Optional.of(Scales.money(value.subtract(before.costBasis()))));
    }

    /**
     * A partial sell keeps part of the quantity open, so it can never take the whole cost: when the rounded share
     * reaches the whole cost (tiny cost, HALF_EVEN up), the smallest USDC unit (0.000001) stays with the open quantity.
     * Regression found by LedgerPropertyTest; without it the position breaks "zero quantity iff zero cost".
     */
    private static BigDecimal keepSmallestUnit(BigDecimal costBasis, BigDecimal share) {
        BigDecimal unit = BigDecimal.ONE.movePointLeft(Scales.MONEY);
        return share.compareTo(costBasis) >= 0
                ? Scales.money(costBasis.subtract(unit).max(BigDecimal.ZERO))
                : share;
    }

    private static void requirePositive(BigDecimal filled) {
        if (filled.signum() <= 0) {
            throw new IllegalArgumentException("A fill quantity is greater than zero");
        }
    }
}
