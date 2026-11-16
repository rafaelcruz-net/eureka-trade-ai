package com.memetrade.botwallet.domain;

/**
 * 261113-trading-real-bot U5 (BR1.4): the first red gate of a release, in the order the gates are read. The names are
 * also the closed label {@code reason} of {@code real_release_blocks_total} (U5-NFR5.1).
 */
public enum BlockedReason {
    NETWORK_MISMATCH,
    KILL_SWITCH_ON,
    DIVERGENCE_OPEN,
    CI_UNREACHABLE,
    CI_RED
}
