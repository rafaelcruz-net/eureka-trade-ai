package com.memetrade.botwallet.domain;

import java.util.Map;

/**
 * 261113-trading-real-bot U5 (BR2.1, performance-design.md): the bot wallet on the network at the last finalized slot:
 * the lamports of its system account and the base units of every mint it holds (all its token accounts, Token and
 * Token-2022).
 */
public record ChainSnapshot(long slot, long lamports, Map<String, Long> tokens) {

    public ChainSnapshot {
        tokens = Map.copyOf(tokens);
    }
}
