package com.memetrade.txsigner.domain;

/** Errors of the TxSigner API with their HTTP status (contract R1). */
public enum SignerError {
    REAL_DISABLED(403),
    CONSENT_INVALID(422),
    CONSENT_EXPIRED(422),
    VALIDATION_FAILED(400),
    WALLET_NOT_FOUND(404),
    REQUEST_MISMATCH(409),
    KEY_RATE_LIMITED(429),
    RECOVERY_IN_PROGRESS(503),
    RECOVERY_REFUSED(422);

    private final int status;

    SignerError(int status) {
        this.status = status;
    }

    public int status() {
        return status;
    }
}
