package com.memetrade.realexits.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * BR6.4, BR6.7, U7-NFR4.11: the automatic cancellation stopped for 15 minutes or more. {@code needsUserCancellation} of
 * the contract R5 is computed on the read with the injected clock; it never moves the conditional.
 */
public final class CancellationWatch {

    /** BR6.4: the alert and the notice to the person after 15 minutes in CANCELLING (U7-NFR5.7, same threshold). */
    public static final Duration STALLED_AFTER = Duration.ofMinutes(15);

    private CancellationWatch() {}

    public static boolean needsUserCancellation(
            ConditionalState state, Optional<Instant> cancellingSince, Instant now) {
        return state == ConditionalState.CANCELLING
                && cancellingSince.isPresent()
                && Duration.between(cancellingSince.get(), now).compareTo(STALLED_AFTER) >= 0;
    }

    /** Whole seconds from {@code since} to {@code now}, never negative (the gauges of U7-NFR5.3 and U7-NFR5.6b). */
    public static long secondsSince(Instant since, Instant now) {
        return Math.max(0, Duration.between(since, now).getSeconds());
    }
}
