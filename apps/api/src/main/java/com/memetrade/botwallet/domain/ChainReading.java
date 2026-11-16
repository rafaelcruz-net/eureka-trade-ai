package com.memetrade.botwallet.domain;

import java.util.Objects;

/**
 * 261113-trading-real-bot U5 (BR2.1): one read of the network for a reconciliation: whether an entry newer than the
 * cursor of the deposit scan exists (not yet registered: the wallet is not settled), and the snapshot.
 */
public record ChainReading(boolean unreadIncoming, ChainSnapshot snapshot) {

    public ChainReading {
        Objects.requireNonNull(snapshot, "snapshot");
    }
}
