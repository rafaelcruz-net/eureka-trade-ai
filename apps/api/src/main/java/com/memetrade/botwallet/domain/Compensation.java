package com.memetrade.botwallet.domain;

import java.util.Objects;

/**
 * 261113-trading-real-bot U5 (BR2.3): one item of a compensation: an asset ({@link #SOL} for lamports, otherwise the mint)
 * and the signed amount to append to the REAL ledger (lamports or base units).
 */
public record Compensation(String asset, long amount) {

    /** The asset of the lamports of the system account. */
    public static final String SOL = "SOL";

    public Compensation {
        Objects.requireNonNull(asset, "asset");
    }

    public boolean isSol() {
        return SOL.equals(asset);
    }
}
