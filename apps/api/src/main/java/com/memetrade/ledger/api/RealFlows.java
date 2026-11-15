package com.memetrade.ledger.api;

/**
 * M2 {@code flowsSince} (261113-trading-real-bot U3, entities.md RealFlows, BR4.3): the deposits and the withdrawals of
 * a bot wallet recorded AFTER an instant, in lamports, both as positive amounts. A withdrawal counts with its network
 * fee (entries with a {@code wd:} reference).
 */
public record RealFlows(long depositedLamports, long withdrawnLamports) {

    public static final RealFlows NONE = new RealFlows(0, 0);

    public RealFlows {
        if (depositedLamports < 0 || withdrawnLamports < 0) {
            throw new IllegalArgumentException("flows are positive amounts");
        }
    }
}
