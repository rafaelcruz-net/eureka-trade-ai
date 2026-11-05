package com.memetrade.bots.domain;

import com.memetrade.riskcontrol.api.BotLimitsVersionRef;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * The values of one limits version (entities.md {@code BotLimitsVersion}): USDC amounts with 6 places, percentages in
 * points with 4 places. Coherence is checked by {@link BotConfigValidator} (BR1.5) before a version is recorded.
 */
public record BotLimits(
        BigDecimal maxOrderSize,
        BigDecimal maxTokenExposure,
        BigDecimal maxTotalExposure,
        BigDecimal maxDailyLoss,
        BigDecimal maxDrawdownPct,
        BigDecimal maxSlippagePct,
        BigDecimal minLiquidity,
        long minIntervalSeconds) {

    public BotLimits {
        Objects.requireNonNull(maxOrderSize, "maxOrderSize");
        Objects.requireNonNull(maxTokenExposure, "maxTokenExposure");
        Objects.requireNonNull(maxTotalExposure, "maxTotalExposure");
        Objects.requireNonNull(maxDailyLoss, "maxDailyLoss");
        Objects.requireNonNull(maxDrawdownPct, "maxDrawdownPct");
        Objects.requireNonNull(maxSlippagePct, "maxSlippagePct");
        Objects.requireNonNull(minLiquidity, "minLiquidity");
    }

    /** The values handed to the barrier of U6 for this version (C14). */
    public BotLimitsVersionRef toRef(long version) {
        return new BotLimitsVersionRef(
                version,
                maxOrderSize,
                maxTokenExposure,
                maxTotalExposure,
                maxSlippagePct,
                minLiquidity,
                minIntervalSeconds,
                maxDailyLoss,
                maxDrawdownPct);
    }
}
