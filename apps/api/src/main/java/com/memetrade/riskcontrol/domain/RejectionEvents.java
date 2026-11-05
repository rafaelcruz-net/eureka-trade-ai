package com.memetrade.riskcontrol.domain;

/**
 * Port of the audit trail (C9) for the barrier: one {@code ORDER_REJECTED} per rejection, in the caller's transaction
 * (BR3.5, NFR9.4). The details carry identifiers and closed values only, never a wallet, a secret or market values.
 */
public interface RejectionEvents {

    void rejected(RejectionRecord rejection);
}
