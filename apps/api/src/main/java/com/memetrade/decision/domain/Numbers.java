package com.memetrade.decision.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Exact arithmetic of the decision (rules.md conventions): percentages are points with 4 places and unit prices have
 * 12 places, both {@code HALF_EVEN}; ratios keep 12 places. Never floating point.
 */
final class Numbers {

    static final BigDecimal ONE = new BigDecimal("1.0000");
    static final BigDecimal HUNDRED = new BigDecimal("100");
    static final int PERCENT_SCALE = 4;
    static final int PRICE_SCALE = 12;
    static final int RATIO_SCALE = 12;

    private Numbers() {}

    /** {@code round4((to - from) / from x 100)}; {@code from} is not zero (checked by the caller). */
    static BigDecimal changePct(BigDecimal from, BigDecimal to) {
        return to.subtract(from).multiply(HUNDRED).divide(from, PERCENT_SCALE, RoundingMode.HALF_EVEN);
    }

    static BigDecimal ratio(BigDecimal value, BigDecimal reference) {
        return value.divide(reference, RATIO_SCALE, RoundingMode.HALF_EVEN);
    }

    /** The number of 5-minute buckets of a window in minutes (BR4.3, BR4.4: the parameter is a multiple of 5). */
    static int buckets(BigDecimal windowMinutes) {
        return windowMinutes
                .divide(new BigDecimal("5"), 0, RoundingMode.UNNECESSARY)
                .intValueExact();
    }
}
