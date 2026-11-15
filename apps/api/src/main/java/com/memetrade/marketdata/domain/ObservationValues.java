package com.memetrade.marketdata.domain;

import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.UnknownReason;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/** Turns a stored observation row into a {@link MarketValue}, computing {@code stale} at read time (BR3.1, BR3.4). */
public final class ObservationValues {

    private ObservationValues() {}

    public static MarketValue fromStored(
            String status, BigDecimal value, String unknownReason, SourceRef source, Instant observedAt, Instant now) {
        return fromStored(MarketField.PRICE, status, value, unknownReason, source, observedAt, now);
    }

    /** U2 (BR1.4): {@code stale} by the limit of {@code field}. */
    public static MarketValue fromStored(
            MarketField field,
            String status,
            BigDecimal value,
            String unknownReason,
            SourceRef source,
            Instant observedAt,
            Instant now) {
        if ("KNOWN".equals(status)) {
            return new MarketValue.Known(value, source, observedAt, Freshness.isStale(field, observedAt, now));
        }
        return new MarketValue.Unknown(UnknownReason.valueOf(unknownReason), Optional.of(source));
    }

    /** A field with no observation row yet. */
    public static MarketValue notYetObserved() {
        return new MarketValue.Unknown(UnknownReason.NOT_YET_OBSERVED, Optional.empty());
    }
}
