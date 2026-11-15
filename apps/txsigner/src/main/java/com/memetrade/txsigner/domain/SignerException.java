package com.memetrade.txsigner.domain;

/** A refusal of the TxSigner domain; the message never carries a consent, a transaction nor a key reference. */
public class SignerException extends RuntimeException {

    private final SignerError error;
    private final String reason;

    public SignerException(SignerError error, String reason) {
        super(error + (reason == null ? "" : " (" + reason + ")"));
        this.error = error;
        this.reason = reason;
    }

    public SignerError error() {
        return error;
    }

    /** Detailed reason for the audit and the metrics; {@code null} when the error says it all. */
    public String reason() {
        return reason;
    }
}
