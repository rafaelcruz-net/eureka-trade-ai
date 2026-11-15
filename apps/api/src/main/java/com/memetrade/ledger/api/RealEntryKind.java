package com.memetrade.ledger.api;

/**
 * Kinds of a REAL ledger entry (BR3.5). Balance entries change the total with their sign; RESERVE and RESERVE_RELEASE
 * are always positive and only move the reserved amount. In the U1 only DEPOSIT, WITHDRAWAL, NETWORK_FEE, RESERVE and
 * RESERVE_RELEASE are written; SWAP_* and RENT come with the U4, COMPENSATION with the U5.
 */
public enum RealEntryKind {
    DEPOSIT,
    WITHDRAWAL,
    NETWORK_FEE,
    RENT,
    SWAP_FILL_IN,
    SWAP_FILL_OUT,
    COMPENSATION,
    RESERVE,
    RESERVE_RELEASE;

    public boolean isBalanceEntry() {
        return this != RESERVE && this != RESERVE_RELEASE;
    }
}
