package com.memetrade.botwallet.api;

/**
 * M1 {@code applyModeConsent} (U4, BR7.2): the consent of the raise does not check. {@code code} is the code of the
 * contract ({@code CONSENT_INVALID}, {@code CONSENT_EXPIRED}, {@code REAL_DISABLED}, {@code REAL_NOT_RELEASED},
 * {@code VALIDATION_FAILED}); never the consent nor the signature.
 */
public class RealConsentRefused extends RuntimeException {

    private final String code;

    public RealConsentRefused(String code) {
        super("REAL consent refused: " + code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
