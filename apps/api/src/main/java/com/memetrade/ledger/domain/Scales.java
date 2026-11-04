package com.memetrade.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The scales of the "Escalas" table (contract-summary.md, closed by U7): USDC amounts 6 decimals, unit prices 12,
 * percentage points 4, all {@code HALF_EVEN}; token quantities truncated ({@code DOWN}) to the mint decimals. Each
 * value is rounded ONCE, when it is computed from exact values (BR1.8). Never floating point.
 */
public final class Scales {

    public static final int MONEY = 6;
    public static final int PRICE = 12;
    public static final int PERCENT = 4;
    public static final BigDecimal HUNDRED = new BigDecimal("100");

    private Scales() {}

    public static BigDecimal money(BigDecimal exact) {
        return exact.setScale(MONEY, RoundingMode.HALF_EVEN);
    }

    public static BigDecimal price(BigDecimal exact) {
        return exact.setScale(PRICE, RoundingMode.HALF_EVEN);
    }

    public static BigDecimal percent(BigDecimal exact) {
        return exact.setScale(PERCENT, RoundingMode.HALF_EVEN);
    }

    /** Truncates a token quantity to the decimals of its mint (BR1.7). */
    public static BigDecimal quantity(BigDecimal exact, int decimals) {
        return exact.setScale(decimals, RoundingMode.DOWN);
    }

    /** {@code 0.000000}: the zero of a USDC amount (a real zero, never a stand-in for an unknown value). */
    public static BigDecimal zeroMoney() {
        return BigDecimal.ZERO.setScale(MONEY);
    }
}
