package com.memetrade.botwallet.domain;

import java.util.Optional;

/**
 * 261113-trading-real-bot U5 (BR4.1, S19): the administrator only lowers the cap of a person (raising needs the consent
 * of the person, BR2.2 of the U1).
 */
public final class CapRules {

    private CapRules() {}

    /** The stable code of the refusal, or empty when {@code requested} lowers {@code current}. */
    public static Optional<String> refusal(long current, long requested) {
        if (requested < 0) {
            return Optional.of("VALIDATION_FAILED");
        }
        if (requested >= current) {
            return Optional.of("CAP_ONLY_LOWERED");
        }
        return Optional.empty();
    }
}
