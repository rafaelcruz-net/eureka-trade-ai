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
    UPSTREAM,
    /** 261113-trading-real-bot U5 (BR2.5): the database was restored; global until every wallet is clean. */
    RESTORE,
    /** U5 (BR2.5): the restore check at the start of the worker did not finish yet (the RPC did not answer). */
    RESTORE_CHECK_PENDING
}
