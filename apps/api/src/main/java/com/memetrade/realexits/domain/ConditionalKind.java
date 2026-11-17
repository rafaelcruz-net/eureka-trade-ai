package com.memetrade.realexits.domain;

/** BR3.1: a stop, a take-profit, or both together where one cancels the other (OCO). */
public enum ConditionalKind {
    STOP,
    TAKE_PROFIT,
    OCO;

    public boolean hasStop() {
        return this != TAKE_PROFIT;
    }

    public boolean hasTakeProfit() {
        return this != STOP;
    }
}
