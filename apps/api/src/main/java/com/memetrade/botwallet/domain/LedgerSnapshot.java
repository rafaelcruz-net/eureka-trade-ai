package com.memetrade.botwallet.domain;

import java.util.Map;

/**
 * 261113-trading-real-bot U5 (BR2.1, U5-NFR2.1): the REAL ledger of one bot wallet (M2 only, never PAPER): the total of
 * its balance entries in lamports and the base units of every mint (swap fills and compensations).
 */
public record LedgerSnapshot(long lamports, Map<String, Long> tokens) {

    public LedgerSnapshot {
        tokens = Map.copyOf(tokens);
    }
}
