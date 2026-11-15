package com.memetrade.botwallet.domain;

/** Reason of a REAL pause (BR2.3); only USER is removed by the person. */
public enum PauseReason {
    USER,
    DIVERGENCE,
    DAILY_LOSS,
    KILL_SWITCH,
    ADMIN,
    UPSTREAM
}
