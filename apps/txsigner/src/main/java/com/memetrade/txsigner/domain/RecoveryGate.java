package com.memetrade.txsigner.domain;

/** Current recovery phase, read by the signing and key services before every decision. */
public interface RecoveryGate {

    RecoveryPhase phase();

    /** Gate of a TxSigner that is not in recovery mode. */
    static RecoveryGate normal() {
        return () -> RecoveryPhase.NORMAL;
    }
}
