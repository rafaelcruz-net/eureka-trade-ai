package com.memetrade.decision.domain;

/** The action of a decision (C4 {@code Decision.action}); only BUY and SELL become a candidate order (BR3.4). */
public enum DecisionAction {
    BUY,
    SELL,
    HOLD,
    NO_TRADE
}
