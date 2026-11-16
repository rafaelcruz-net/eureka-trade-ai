package com.memetrade.botwallet.domain;

/**
 * 261113-trading-real-bot U5 (entities.md RealRelease, functional-spec.md "Máquina de estados — RealRelease"): the state
 * of a release. Only {@code ACTIVE} is in force (BR1.1); {@code REVOKED} is final.
 */
public enum ReleaseState {
    QUARANTINE,
    ACTIVE,
    BLOCKED,
    REVOKED
}
