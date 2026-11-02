package com.memetrade.riskassessment.domain;

import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.riskassessment.api.FactorId;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * Result of one factor (entities.md): weight, input used (KNOWN or UNKNOWN), factor risk (100 when UNKNOWN, BR3.4)
 * and contribution {@code weight / 100 x risk} at scale 4, empty (shown as UNKNOWN) when the input is UNKNOWN.
 */
public record FactorResult(
        FactorId factorId, BigDecimal weight, MarketValue input, int factorRisk, Optional<BigDecimal> contribution) {

    public boolean isUnknown() {
        return !input.isKnown();
    }
}
