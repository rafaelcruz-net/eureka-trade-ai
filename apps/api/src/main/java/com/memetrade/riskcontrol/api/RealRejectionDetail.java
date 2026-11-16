package com.memetrade.riskcontrol.api;

/**
 * 261113-trading-real-bot U3 (entities.md GateDecision): the detail of a refused REAL order. {@code NONE} is used only
 * with {@link RealRejectionCode#SIMULATED_SOURCE_ACTIVE}, which has no detail.
 */
public enum RealRejectionDetail {
    NONE,
    NOT_RELEASED,
    REAL_DISABLED,
    NO_BOT_WALLET,
    PAUSED_USER,
    PAUSED_DAILY_LOSS,
    PAUSED_ADMIN,
    PAUSED_DIVERGENCE,
    PAUSED_KILL_SWITCH,
    PAUSED_UPSTREAM,
    /** 261113-trading-real-bot U5 (BR2.5): the database was restored; global until the administrator confirms. */
    PAUSED_RESTORE,
    /** U5 (BR2.5): the restore check of the worker did not finish yet. */
    PAUSED_RESTORE_CHECK_PENDING,
    NO_ASSESSMENT,
    BLOCKED,
    SIGNAL_STALE,
    SIGNAL_UNKNOWN,
    POSITION_PRICE_UNKNOWN,
    ORDER_SIZE,
    DAILY_ORDER_COUNT,
    DAILY_LOSS,
    CAP,
    FREE_BALANCE
}
