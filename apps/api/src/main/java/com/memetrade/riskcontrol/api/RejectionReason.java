package com.memetrade.riskcontrol.api;

/**
 * Why the barrier refused an order (C21 enum). The declaration order IS the fixed order of BR3.1: the reasons of a
 * rejection are sorted by it and the first one is the main reason shown on the screen. Never reorder.
 */
public enum RejectionReason {
    ORDER_SIZE,
    TOKEN_EXPOSURE,
    TOTAL_EXPOSURE,
    SLIPPAGE,
    MIN_LIQUIDITY,
    STALE_DATA,
    INTERVAL,
    DUPLICATE,
    INSUFFICIENT_PAPER_BALANCE,
    DAILY_LOSS,
    DRAWDOWN
}
