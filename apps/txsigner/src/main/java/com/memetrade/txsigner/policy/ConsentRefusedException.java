package com.memetrade.txsigner.policy;

/** A consent that does not pass the checks of BR1.5 and BR5.3; the message never contains the consent text. */
public class ConsentRefusedException extends RuntimeException {

    private final ConsentRefusal refusal;

    public ConsentRefusedException(ConsentRefusal refusal) {
        super("Consent refused: " + refusal);
        this.refusal = refusal;
    }

    public ConsentRefusal refusal() {
        return refusal;
    }

    /** {@code CONSENT_EXPIRED} or {@code CONSENT_INVALID}. */
    public String code() {
        return refusal.code();
    }
}
