package com.memetrade.riskcontrol.api;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The values of the limits version in force, handed by U5 on the call (C14 {@code BotLimitsVersionRef}, entities.md
 * {@code LimitsSnapshot}). U6 keeps only the version number. U5 validates the coherence when the limits are created
 * (AC4.2.3); this check is a defence (BR1.2) and a value out of its domain throws.
 *
 * @param version the limits version, at least 1; the same version goes to the execution
 * @param maxOrderSize USDC, greater than zero
 * @param maxTokenExposure USDC, greater than zero and at most {@code maxTotalExposure}
 * @param maxTotalExposure USDC, greater than zero
 * @param maxSlippagePct percentage points, 0 to 100
 * @param minLiquidity USDC, zero or more
 * @param minIntervalSeconds seconds between two operations of the bot, zero or more
 * @param maxDailyLoss USDC, greater than zero
 * @param maxDrawdownPct percentage points, above 0 and at most 100
 */
public record BotLimitsVersionRef(
        long version,
        BigDecimal maxOrderSize,
        BigDecimal maxTokenExposure,
        BigDecimal maxTotalExposure,
        BigDecimal maxSlippagePct,
        BigDecimal minLiquidity,
        long minIntervalSeconds,
        BigDecimal maxDailyLoss,
        BigDecimal maxDrawdownPct) {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public BotLimitsVersionRef {
        if (version < 1) {
            throw invalid("version must be at least 1");
        }
        requirePositive(maxOrderSize, "maxOrderSize");
        requirePositive(maxTokenExposure, "maxTokenExposure");
        requirePositive(maxTotalExposure, "maxTotalExposure");
        requirePercent(maxSlippagePct, "maxSlippagePct", false);
        Objects.requireNonNull(minLiquidity, "minLiquidity");
        if (minLiquidity.signum() < 0) {
            throw invalid("minLiquidity must not be negative");
        }
        if (minIntervalSeconds < 0) {
            throw invalid("minIntervalSeconds must not be negative");
        }
        requirePositive(maxDailyLoss, "maxDailyLoss");
        requirePercent(maxDrawdownPct, "maxDrawdownPct", true);
        if (maxTokenExposure.compareTo(maxTotalExposure) > 0) {
            throw invalid("maxTokenExposure must not exceed maxTotalExposure");
        }
    }

    private static void requirePositive(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() <= 0) {
            throw invalid(name + " must be greater than zero");
        }
    }

    private static void requirePercent(BigDecimal value, String name, boolean aboveZero) {
        Objects.requireNonNull(value, name);
        boolean low = aboveZero ? value.signum() <= 0 : value.signum() < 0;
        if (low || value.compareTo(HUNDRED) > 0) {
            throw invalid(name + " must be within its percentage domain");
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Limits out of their domain (BR1.2): " + message);
    }
}
