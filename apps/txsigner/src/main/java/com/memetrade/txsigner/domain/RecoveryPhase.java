package com.memetrade.txsigner.domain;

/**
 * Phase of the TxSigner with respect to the recovery mode (security-design.md, NFR4.13): NORMAL signs per the policy;
 * WAITING (the first five minutes after a restore) signs nothing; WITHDRAWALS_ONLY signs only withdrawals and refuses
 * creations and raises until the recovery completes.
 */
public enum RecoveryPhase {
    NORMAL,
    WAITING,
    WITHDRAWALS_ONLY
}
