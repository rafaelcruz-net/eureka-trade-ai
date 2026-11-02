package com.memetrade.riskassessment.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * One factor of a weight version: its weight in percentage points (scale 4) and its curve of 2 to 8 points
 * (entities.md).
 */
public record FactorDefinition(FactorId factorId, BigDecimal weight, List<CurvePoint> curve) {

    public FactorDefinition {
        curve = curve == null ? null : List.copyOf(curve);
    }
}
