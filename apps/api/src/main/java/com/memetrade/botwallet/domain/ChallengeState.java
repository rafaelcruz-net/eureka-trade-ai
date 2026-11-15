package com.memetrade.botwallet.domain;

/** State of a consent challenge; IN_USE while the activation waits for the TxSigner (BR1.10). */
public enum ChallengeState {
    PENDING,
    IN_USE,
    CONSUMED,
    EXPIRED
}
