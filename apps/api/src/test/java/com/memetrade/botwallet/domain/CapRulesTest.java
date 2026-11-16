package com.memetrade.botwallet.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 9 of the U5, written BEFORE the code (BR4.1, S18, S19): the administrator only lowers the cap of a person; a cap
 * equal to the current one or above it is refused CAP_ONLY_LOWERED, and a negative one is invalid.
 */
@Tag("u5-real-operations")
class CapRulesTest {

    private static final long SOL = 1_000_000_000L;

    @Test
    void aLowerCapIsAccepted() {
        assertThat(CapRules.refusal(10 * SOL, 3 * SOL)).isEmpty();
        assertThat(CapRules.refusal(10 * SOL, 0L)).isEmpty();
    }

    /** S19: a cap of 3 SOL cannot go to 5 SOL. */
    @Test
    void aHigherOrEqualCapIsRefusedCapOnlyLowered() {
        assertThat(CapRules.refusal(3 * SOL, 5 * SOL)).contains("CAP_ONLY_LOWERED");
        assertThat(CapRules.refusal(3 * SOL, 3 * SOL)).contains("CAP_ONLY_LOWERED");
    }

    @Test
    void aNegativeCapIsInvalid() {
        assertThat(CapRules.refusal(3 * SOL, -1L)).contains("VALIDATION_FAILED");
    }
}
