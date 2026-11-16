package com.memetrade.botwallet.domain;

import java.util.List;
import java.util.Objects;

/**
 * 261113-trading-real-bot U5 (entities.md ReconciliationRun): the result of one reconciliation. The slot and both
 * balances exist only when it compared ({@link ReconciliationResult#measured()}); the token diffs list only the mints
 * whose difference is not zero.
 */
public record ReconciliationOutcome(
        ReconciliationResult result,
        Long slot,
        Long chainLamports,
        Long ledgerLamports,
        List<TokenDiff> tokenDiffs,
        int inTransit) {

    public ReconciliationOutcome {
        Objects.requireNonNull(result, "result");
        tokenDiffs = List.copyOf(tokenDiffs);
    }

    static ReconciliationOutcome notCompared(ReconciliationResult result, int inTransit) {
        return new ReconciliationOutcome(result, null, null, null, List.of(), inTransit);
    }

    /** Network minus ledger in lamports; zero when it did not compare. */
    public long lamportsDiff() {
        return chainLamports == null ? 0 : Math.subtractExact(chainLamports, ledgerLamports);
    }
}
