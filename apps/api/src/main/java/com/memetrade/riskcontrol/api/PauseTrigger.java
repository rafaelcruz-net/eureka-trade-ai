package com.memetrade.riskcontrol.api;

/**
 * Pause reasons the barrier indicates (BR3.3, US4.7). U6 never pauses a bot: U5 applies each trigger in the same
 * cycle transaction and owns the midnight reset and the resumption.
 */
public enum PauseTrigger {
    DAILY_LOSS,
    DRAWDOWN
}
