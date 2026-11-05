package com.memetrade.bots.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * BR1.6 (Q8, Q13): the default limits of the wizard, proportional to the virtual balance and rounded once to USDC with
 * 6 places, {@code HALF_EVEN}. Order 10%, token exposure 20%, total exposure 50%, daily loss 5%; drawdown 10%, slippage
 * 2%, minimum liquidity 10.000 USDC and 240 s between operations. A limit the person edited is never recalculated (the
 * screen keeps it).
 */
public final class DefaultLimits {

    public static final BigDecimal DRAWDOWN_PCT = new BigDecimal("10.0000");
    public static final BigDecimal SLIPPAGE_PCT = new BigDecimal("2.0000");
    public static final BigDecimal MIN_LIQUIDITY = new BigDecimal("10000.000000");
    public static final long MIN_INTERVAL_SECONDS = 240;

    private static final int USDC_SCALE = 6;

    private DefaultLimits() {}

    public static BotLimits forBalance(BigDecimal virtualBalance) {
        return new BotLimits(
                share(virtualBalance, "0.10"),
                share(virtualBalance, "0.20"),
                share(virtualBalance, "0.50"),
                share(virtualBalance, "0.05"),
                DRAWDOWN_PCT,
                SLIPPAGE_PCT,
                MIN_LIQUIDITY,
                MIN_INTERVAL_SECONDS);
    }

    private static BigDecimal share(BigDecimal balance, String fraction) {
        return balance.multiply(new BigDecimal(fraction)).setScale(USDC_SCALE, RoundingMode.HALF_EVEN);
    }
}
