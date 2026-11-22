package com.memetrade.bots.application;

/**
 * 261121-paper-real-unico U1 (BR2.5, NFR4.5): a source of the REAL eligibility failed or the deadline ran out before a
 * reason was decided. Fails closed: the read (C2) and the REAL creation (C1) answer {@code 503 SERVICE_UNAVAILABLE},
 * nothing is written and no idempotent answer is stored. The message carries no data.
 */
public class EligibilitySourceUnavailable extends RuntimeException {

    private final String label;

    public EligibilitySourceUnavailable(String label, Throwable failure) {
        super("The REAL eligibility could not be read", failure);
        this.label = label;
    }

    /** {@code TIMEOUT} or the simple name of the class of the failure; never its message. */
    public String causeLabel() {
        return label;
    }
}
