package com.memetrade.botwallet.domain;

/**
 * 261113-trading-real-bot U5 (BR1.2, BR1.3): why a release is refused, with the HTTP status of the stable code of the
 * ProblemDetail (R4).
 */
public enum ReleaseRefusal {
    NO_BOT_WALLET(422),
    TERMS_V3_MISSING(422),
    RELEASE_EXISTS(409),
    FOUNDER_FIRST(409),
    PILOT_FULL(409);

    private final int status;

    ReleaseRefusal(int status) {
        this.status = status;
    }

    public int status() {
        return status;
    }
}
