package com.memetrade.marketdata.domain;

import com.memetrade.marketdata.api.MarketField;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Relative divergence tolerance per field in percentage points (BR1.7, Q7). The comparison never divides and never
 * rounds: another value diverges when {@code |other - shown| x 100 > tolerance x |shown|}; with a shown value of 0 any
 * other non-zero value diverges. CREATED_AT has no tolerance and is never compared.
 */
public final class DivergencePolicy {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final Map<MarketField, BigDecimal> tolerances;

    public DivergencePolicy(Map<MarketField, BigDecimal> tolerances) {
        this.tolerances = new EnumMap<>(MarketField.class);
        tolerances.forEach((field, tolerance) -> this.tolerances.put(field, Objects.requireNonNull(tolerance)));
        this.tolerances.remove(MarketField.CREATED_AT);
    }

    /** The same tolerance for PRICE, LIQUIDITY, VOLUME_24H and HOLDERS (MARKET_DIVERGENCE_TOLERANCE_PERCENT). */
    public static DivergencePolicy uniform(BigDecimal tolerancePercent) {
        Map<MarketField, BigDecimal> tolerances = new EnumMap<>(MarketField.class);
        for (MarketField field : MarketField.values()) {
            tolerances.put(field, tolerancePercent);
        }
        return new DivergencePolicy(tolerances);
    }

    public Optional<BigDecimal> tolerance(MarketField field) {
        return Optional.ofNullable(tolerances.get(field));
    }

    public boolean diverges(MarketField field, BigDecimal shown, BigDecimal other) {
        BigDecimal tolerance = tolerances.get(field);
        if (tolerance == null) {
            return false;
        }
        BigDecimal difference = other.subtract(shown).abs().multiply(HUNDRED);
        return difference.compareTo(tolerance.multiply(shown.abs())) > 0;
    }
}
