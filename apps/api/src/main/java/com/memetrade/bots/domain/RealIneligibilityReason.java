package com.memetrade.bots.domain;

/**
 * 261121-paper-real-unico U1 (BR2.1, C1, C2): why a person cannot create a REAL bot now, in the order of precedence;
 * only the strongest one is ever answered. The names are the values of the contract.
 */
public enum RealIneligibilityReason {
    KILL_SWITCH,
    REAL_DISABLED,
    WALLET_INACTIVE,
    NOT_RELEASED,
    QUARANTINE
}
