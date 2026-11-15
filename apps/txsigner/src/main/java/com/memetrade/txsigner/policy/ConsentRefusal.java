package com.memetrade.txsigner.policy;

/**
 * Why a consent was refused. Only the reason goes to the audit and the metrics; the answer to the api carries just
 * {@code CONSENT_EXPIRED} or {@code CONSENT_INVALID} (contract R1).
 */
public enum ConsentRefusal {
    MALFORMED,
    PURPOSE_MISMATCH,
    NETWORK_MISMATCH,
    MAIN_WALLET_MISMATCH,
    BOT_WALLET_MISMATCH,
    MODE_NOT_ALLOWED,
    CAP_ABOVE_MAXIMUM,
    BAD_SIGNATURE,
    NONCE_USED,
    EXPIRED;

    public String code() {
        return this == EXPIRED ? "CONSENT_EXPIRED" : "CONSENT_INVALID";
    }
}
