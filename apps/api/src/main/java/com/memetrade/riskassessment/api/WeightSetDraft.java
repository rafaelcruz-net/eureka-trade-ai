package com.memetrade.riskassessment.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * Content of a weight version as proposed by the administrator (F7): validated by BR4.1 before anything is written.
 *
 * @param lowMax highest indicator of the LOW band
 * @param mediumMax highest indicator of the MEDIUM band
 * @param minCoverage minimum coverage in percentage points (scale 4)
 */
public record WeightSetDraft(List<FactorDefinition> factors, int lowMax, int mediumMax, BigDecimal minCoverage) {

    public WeightSetDraft {
        factors = factors == null ? null : List.copyOf(factors);
    }
}
