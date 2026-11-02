package com.memetrade.marketdata.api;

import java.util.List;
import java.util.Objects;

/**
 * The displayed value of one field of a token after joining the sources (F2, entities.md): the most recent KNOWN value
 * of a source that is not FAILED, or UNKNOWN with its reason. {@code origins} are the KNOWN, not stale values of each
 * source used for the divergence check; {@code divergent} is true when another source differs beyond the tolerance of
 * the field (BR1.7). Never stored.
 */
public record ResolvedField(MarketValue value, boolean divergent, List<MarketValue.Known> origins) {

    public ResolvedField {
        Objects.requireNonNull(value, "value");
        origins = List.copyOf(origins);
    }

    public static ResolvedField of(MarketValue value) {
        return new ResolvedField(value, false, value instanceof MarketValue.Known known ? List.of(known) : List.of());
    }

    /** The origins other than the displayed one, shown next to a divergent field (BR5.7). */
    public List<MarketValue.Known> otherOrigins() {
        if (!(value instanceof MarketValue.Known shown)) {
            return List.of();
        }
        return origins.stream()
                .filter(origin ->
                        !origin.source().sourceId().equals(shown.source().sourceId()))
                .toList();
    }
}
