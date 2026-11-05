package com.memetrade.bots.worker;

/**
 * The cause label of {@code bots.cycle.errors} (NFR9.2): where the cycle was when it failed. Never a person, bot or
 * token label.
 */
public enum CycleCause {
    MARKET_DATA,
    RISK_GATE,
    EXECUTION,
    DATABASE,
    OTHER
}
