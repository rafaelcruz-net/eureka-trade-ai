package com.memetrade.botwallet.domain;

/** 261113-trading-real-bot U5 (entities.md ReconciliationRun): what started a reconciliation. */
public enum ReconciliationTrigger {
    ORDER_CONFIRMED,
    SCHEDULE,
    AFTER_COMPENSATION,
    AFTER_RESTORE
}
