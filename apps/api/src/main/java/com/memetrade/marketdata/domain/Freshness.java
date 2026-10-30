package com.memetrade.marketdata.domain;

import java.time.Duration;
import java.time.Instant;

/** BR3.4: a value is stale when its age is STRICTLY above 5 minutes, measured with the injectable clock. */
public final class Freshness {

    public static final Duration STALE_AFTER = Duration.ofMinutes(5);

    private Freshness() {}

    public static boolean isStale(Instant observedAt, Instant now) {
        return Duration.between(observedAt, now).compareTo(STALE_AFTER) > 0;
    }
}
