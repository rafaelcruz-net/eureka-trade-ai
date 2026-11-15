package com.memetrade.bots.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * 261113-trading-real-bot U4 (BR1.2, NFR10.1): the decision engine works in dollars and whole tokens; a REAL order
 * travels in lamports and base units. Exact decimals only, rounded against spending more; an unknown price is never
 * zero: it gives no order at all.
 */
public final class RealSizing {

    private static final BigDecimal LAMPORTS_PER_SOL = BigDecimal.valueOf(1_000_000_000L);
    private static final int PRICE_SCALE = 18;

    private RealSizing() {}

    /**
     * The lamports of a buy of {@code quantity} whole tokens: {@code floor(quantity x tokenUsd / solUsd x 10^9)}; empty
     * when a price is unknown or the value does not reach one lamport.
     */
    public static Optional<Long> buyLamports(
            BigDecimal quantity, Optional<BigDecimal> tokenUsd, Optional<BigDecimal> solUsd) {
        if (tokenUsd.isEmpty() || solUsd.isEmpty() || solUsd.get().signum() <= 0 || quantity.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal lamports = quantity.multiply(tokenUsd.get())
                .multiply(LAMPORTS_PER_SOL)
                .divide(solUsd.get(), PRICE_SCALE, RoundingMode.DOWN)
                .setScale(0, RoundingMode.DOWN);
        return lamports.signum() > 0 ? Optional.of(lamports.longValueExact()) : Optional.empty();
    }

    /** The base units of {@code quantity} whole tokens, rounded down; empty below one base unit. */
    public static Optional<Long> baseUnits(BigDecimal quantity, int decimals) {
        BigDecimal units = quantity.movePointRight(decimals).setScale(0, RoundingMode.DOWN);
        return units.signum() > 0 ? Optional.of(units.longValueExact()) : Optional.empty();
    }

    /** Whole tokens of {@code baseUnits}. */
    public static BigDecimal tokens(long baseUnits, int decimals) {
        return BigDecimal.valueOf(baseUnits).movePointLeft(decimals);
    }

    /** Dollars of {@code lamports} at {@code solUsd}, with 6 places (the scale of the virtual balance). */
    public static BigDecimal dollarsOfLamports(long lamports, BigDecimal solUsd) {
        return BigDecimal.valueOf(lamports).multiply(solUsd).divide(LAMPORTS_PER_SOL, 6, RoundingMode.DOWN);
    }
}
