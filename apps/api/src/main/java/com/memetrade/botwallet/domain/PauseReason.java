package com.memetrade.botwallet.domain;

/**
 * Reason of a REAL pause (BR2.3); only USER is removed by the person. 261113-trading-real-bot U5 (entities.md "PauseReason
 * ampliado", BR2.5): {@code RESTORE} and {@code RESTORE_CHECK_PENDING} are GLOBAL, like {@code KILL_SWITCH} since the U5:
 * the M1 {@code readRealState} adds them from the live read of the restore marker, never from a row per person.
 */
public enum PauseReason {
    USER,
    DIVERGENCE,
    DAILY_LOSS,
    KILL_SWITCH,
    ADMIN,
    UPSTREAM,
    RESTORE,
    RESTORE_CHECK_PENDING
}
