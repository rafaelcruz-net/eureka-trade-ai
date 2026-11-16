package com.memetrade.botwallet.domain;

import java.util.Objects;

/** 261113-trading-real-bot U5 (entities.md ReconciliationRun.tokenDiffs): one mint on the network and in the ledger. */
public record TokenDiff(String mint, long chain, long ledger) {

    public TokenDiff {
        Objects.requireNonNull(mint, "mint");
    }

    /** Network minus ledger, in base units, with its sign. */
    public long diff() {
        return Math.subtractExact(chain, ledger);
    }
}
