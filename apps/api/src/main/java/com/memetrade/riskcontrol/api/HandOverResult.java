package com.memetrade.riskcontrol.api;

/**
 * 261113-trading-real-bot U3 (BR6.2): the answer of {@link RealRiskGate#handOver}. Only {@code HANDED_OVER} and
 * {@code ALREADY_HANDED_OVER} let the U4 create the order; {@code RESERVATION_EXPIRED} and {@code NOT_FOUND} never do.
 */
public enum HandOverResult {
    HANDED_OVER,
    ALREADY_HANDED_OVER,
    RESERVATION_EXPIRED,
    NOT_FOUND;

    public boolean mayCreateOrder() {
        return this == HANDED_OVER || this == ALREADY_HANDED_OVER;
    }
}
