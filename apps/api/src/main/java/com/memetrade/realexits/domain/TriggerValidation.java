package com.memetrade.realexits.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * BR3.1 to BR3.4 and the first layer of BR2.6 (U7-NFR1.4), pure and in exact decimals: the prices of a conditional
 * against the current price in USD, before any call to the Jupiter or the TxSigner. The checks run in this order and
 * the first refusal ends them:
 *
 * <ol>
 *   <li>each kind carries exactly its prices, positive, with at most 18 decimal places ({@code numeric(38,18)});
 *   <li>a current price is known (BR3.3);
 *   <li>in an OCO the take-profit is above the stop;
 *   <li>the stop is below the current price and the take-profit above it;
 *   <li>the stop is at least 50% of the current price and the take-profit at most 10 times it (BR2.6).
 * </ol>
 */
public final class TriggerValidation {

    /** BR2.6: the stop may not be below half of the current price. */
    static final BigDecimal MIN_STOP_RATIO = new BigDecimal("0.5");

    /** BR2.6: the take-profit may not be above 10 times the current price. */
    static final BigDecimal MAX_TAKE_PROFIT_RATIO = BigDecimal.TEN;

    private static final int MAX_SCALE = 18;
    private static final int MAX_PRECISION = 38;
    private static final BigDecimal BPS = new BigDecimal("10000");

    private TriggerValidation() {}

    public static Optional<TriggerRefusal> check(
            ConditionalKind kind, Optional<BigDecimal> stop, Optional<BigDecimal> takeProfit, CurrentPrice current) {
        if (kind.hasStop() != stop.isPresent()
                || kind.hasTakeProfit() != takeProfit.isPresent()
                || !stop.map(TriggerValidation::wellFormed).orElse(true)
                || !takeProfit.map(TriggerValidation::wellFormed).orElse(true)) {
            return Optional.of(TriggerRefusal.VALIDATION_FAILED);
        }
        if (!(current instanceof CurrentPrice.Known known)) {
            return Optional.of(TriggerRefusal.PRICE_UNKNOWN);
        }
        BigDecimal price = known.usd();
        if (stop.isPresent() && takeProfit.isPresent() && takeProfit.get().compareTo(stop.get()) <= 0) {
            return Optional.of(TriggerRefusal.TP_NOT_ABOVE_SL);
        }
        if (stop.filter(value -> value.compareTo(price) >= 0).isPresent()
                || takeProfit.filter(value -> value.compareTo(price) <= 0).isPresent()) {
            return Optional.of(TriggerRefusal.TRIGGER_ON_WRONG_SIDE);
        }
        if (stop.filter(value -> value.compareTo(price.multiply(MIN_STOP_RATIO)) < 0)
                        .isPresent()
                || takeProfit
                        .filter(value -> value.compareTo(price.multiply(MAX_TAKE_PROFIT_RATIO)) > 0)
                        .isPresent()) {
            return Optional.of(TriggerRefusal.TRIGGER_OUT_OF_BOUNDS);
        }
        return Optional.empty();
    }

    /**
     * BR2.6, second layer: the floor of the minimum output sent to the TxSigner when the instruction carries one on the
     * network: quantity x stop price x (1 - slippage), never rounded up.
     */
    public static BigDecimal minOutputFloorUsd(BigDecimal quantity, BigDecimal stopPriceUsd, int slippageBps) {
        BigDecimal keep = BPS.subtract(BigDecimal.valueOf(slippageBps)).divide(BPS, 8, RoundingMode.DOWN);
        return quantity.multiply(stopPriceUsd).multiply(keep);
    }

    private static boolean wellFormed(BigDecimal value) {
        BigDecimal plain = value.stripTrailingZeros();
        return value.signum() > 0 && Math.max(plain.scale(), 0) <= MAX_SCALE && plain.precision() <= MAX_PRECISION;
    }
}
