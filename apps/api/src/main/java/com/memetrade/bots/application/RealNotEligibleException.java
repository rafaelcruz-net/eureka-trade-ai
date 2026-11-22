package com.memetrade.bots.application;

import com.memetrade.bots.domain.RealEligibility;
import com.memetrade.bots.domain.RealIneligibilityReason;

/**
 * 261121-paper-real-unico U1 (BR2.4): a REAL creation refused by the eligibility read again inside the command. The
 * command rolls back (no bot, no BOT_CREATED, no stored idempotent answer), the refusal is audited after the rollback
 * and the answer is {@code 409 REAL_NOT_ELIGIBLE} with the strongest reason. The message carries no data.
 */
public class RealNotEligibleException extends RuntimeException {

    private final RealEligibility eligibility;

    public RealNotEligibleException(RealEligibility eligibility) {
        super("The person cannot create a REAL bot now");
        if (eligibility.available()) {
            throw new IllegalArgumentException("An available eligibility is never a refusal");
        }
        this.eligibility = eligibility;
    }

    public RealEligibility eligibility() {
        return eligibility;
    }

    public RealIneligibilityReason reason() {
        return eligibility.reason().orElseThrow();
    }
}
