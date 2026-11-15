package com.memetrade.botwallet.api;

/**
 * M1 (261113-trading-real-bot U3, entities.md RealStateView): the reason of a REAL pause of a bot wallet, as the other
 * modules see it. The same names as the reasons the BotWallet stores in {@code real_preferences.paused_reasons}.
 */
public enum RealPauseReason {
    USER,
    DIVERGENCE,
    DAILY_LOSS,
    KILL_SWITCH,
    ADMIN,
    UPSTREAM
}
