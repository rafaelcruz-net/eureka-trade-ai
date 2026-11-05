package com.memetrade.decision.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * BR4.6: the BUY uses a fraction of the available balance, capped by the maximum per order:
 * {@code value = min(round6(balance x fraction / 100), maxOrderSize)} and
 * {@code quantity = truncate(value / referencePrice)} to the places of the mint. A quantity of zero is a NO_TRADE
 * {@code QUANTITY_BELOW_MINIMUM} for the caller; the barrier of U6 still checks cost, balance and limits.
 */
public final class OrderSizer {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int USDC_SCALE = 6;

    private OrderSizer() {}

    /** The quantity to buy, truncated DOWN to {@code decimals} places; zero when the value does not reach a unit. */
    public static BigDecimal quantity(
            BigDecimal availableBalance,
            BigDecimal buyFractionPct,
            BigDecimal maxOrderSize,
            BigDecimal referencePrice,
            int decimals) {
        Objects.requireNonNull(availableBalance, "availableBalance");
        Objects.requireNonNull(buyFractionPct, "buyFractionPct");
        Objects.requireNonNull(maxOrderSize, "maxOrderSize");
        Objects.requireNonNull(referencePrice, "referencePrice");
        if (referencePrice.signum() <= 0) {
            throw new IllegalArgumentException("The reference price of a buy is above zero");
        }
        if (decimals < 0 || decimals > 18) {
            throw new IllegalArgumentException("The places of a mint go from 0 to 18");
        }
        if (availableBalance.signum() <= 0) {
            return BigDecimal.valueOf(0, decimals);
        }
        BigDecimal share =
                availableBalance.multiply(buyFractionPct).divide(HUNDRED, USDC_SCALE, RoundingMode.HALF_EVEN);
        BigDecimal value = share.min(maxOrderSize);
        return value.divide(referencePrice, decimals, RoundingMode.DOWN);
    }
}
