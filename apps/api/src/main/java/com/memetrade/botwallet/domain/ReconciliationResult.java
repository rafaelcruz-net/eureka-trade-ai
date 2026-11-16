package com.memetrade.botwallet.domain;

/**
 * 261113-trading-real-bot U5 (BR2.1): the result of a reconciliation. CLEAN and DIVERGENT compared the wallet with the
 * ledger; DEFERRED found it not settled (something in transit or an entry not yet read); UNAVAILABLE could not read the
 * network. The names are also the closed label {@code result} of {@code real_reconciliations_total}.
 */
public enum ReconciliationResult {
    CLEAN,
    DIVERGENT,
    DEFERRED,
    UNAVAILABLE;

    /** The run carries the finalized slot and both balances. */
    public boolean measured() {
        return this == CLEAN || this == DIVERGENT;
    }
}
