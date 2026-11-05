package com.memetrade.riskcontrol.api;

/** Side of a candidate order (entities.md); HOLD and NO_TRADE are not orders and never reach the barrier. */
public enum OrderSide {
    BUY,
    SELL
}
