package com.memetrade.riskassessment.domain;

import com.memetrade.marketdata.api.MarketValue;
import java.time.Duration;
import java.time.Instant;

/**
 * BR3.6 with the rule of BR1.1: the stale flag of a stored factor input is never stored; it is computed when the
 * detail is read, from the stored observation time (strictly above 5 minutes is stale).
 */
public final class StaleAtReading {

    public static final Duration STALE_AFTER = Duration.ofMinutes(5);

    private StaleAtReading() {}

    public static MarketValue apply(MarketValue value, Instant now) {
        if (value instanceof MarketValue.Known known) {
            boolean stale = Duration.between(known.observedAt(), now).compareTo(STALE_AFTER) > 0;
            return new MarketValue.Known(known.value(), known.source(), known.observedAt(), stale);
        }
        return value;
    }
}
