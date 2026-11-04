package com.memetrade.ledger.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Versioned fill policy (BR1.1, entities.md SimulationPolicy). Percentages are points with 4 decimals; every fill
 * records the version it used, and a new version applies only to future fills.
 */
public record SimulationPolicy(
        String version,
        BigDecimal spreadPct,
        BigDecimal impactPerConsumedPct,
        BigDecimal feePct,
        BigDecimal maxLiquidityConsumptionPct) {

    /** Policy v1, seeded by the migration 0008 with the same values. */
    public static final SimulationPolicy V1 = new SimulationPolicy(
            "v1",
            new BigDecimal("0.5000"),
            new BigDecimal("1.0000"),
            new BigDecimal("0.3000"),
            new BigDecimal("10.0000"));

    public SimulationPolicy {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(spreadPct, "spreadPct");
        Objects.requireNonNull(impactPerConsumedPct, "impactPerConsumedPct");
        Objects.requireNonNull(feePct, "feePct");
        Objects.requireNonNull(maxLiquidityConsumptionPct, "maxLiquidityConsumptionPct");
    }
}
