package com.memetrade.riskcontrol.api;

/**
 * 261113-trading-real-bot U3 (entities.md OrderIntent, BR1.1): who asks for a REAL order. {@code BOT} is the cycle of a
 * bot (buy or sell); {@code USER_LIQUIDATION} is the "vender tudo e sacar" of the person (sell only), which passes the
 * pauses USER, DAILY_LOSS, ADMIN and UPSTREAM and stops only on KILL_SWITCH and DIVERGENCE.
 */
public enum OrderOrigin {
    BOT,
    USER_LIQUIDATION
}
