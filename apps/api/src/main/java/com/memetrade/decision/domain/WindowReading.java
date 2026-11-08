package com.memetrade.decision.domain;

import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.PriceVolumeWindow;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The 5-minute buckets of one token as the strategies read them (BR4.1, BR4.3, BR4.4). {@code b_n} is the last CLOSED
 * bucket, the one that started 5 minutes before the slot; {@code back(k)} is {@code b_(n-k)}. The open bucket of the
 * slot itself is never used.
 */
final class WindowReading {

    static final Duration BUCKET = Duration.ofMinutes(5);
    static final Duration MAX_AGE = Duration.ofMinutes(5);

    private final Instant lastBucket;
    private final Map<Instant, PriceVolumeWindow.Point> points = new HashMap<>();

    WindowReading(PriceVolumeWindow window, Instant slotStart) {
        this.lastBucket = slotStart.minus(BUCKET);
        for (PriceVolumeWindow.Point point : window.points()) {
            points.put(point.bucketStart(), point);
        }
    }

    /**
     * The data reason of the buckets {@code b_(n-depth)} to {@code b_n}, in the order of BR4.1: a missing bucket is
     * INSUFFICIENT_HISTORY, an UNKNOWN value is UNKNOWN_DATA, a last bucket older than 5 minutes is STALE_DATA.
     *
     * <p>The age of {@code b_n} is measured from the END of the bucket ({@code bucketStart + 5 min}), not from the
     * {@code observedAt} of its sample: U4 keeps the first observation of each bucket, usually taken at its start, so
     * the sample is always older than the bucket it represents. {@code b_n} counts when it ended at most
     * {@link #MAX_AGE} before {@code now} (inclusive). The reference price keeps the {@code observedAt} reading of
     * {@link #fresh}.
     */
    Optional<NoTradeReason> check(int depth, boolean needsVolume, Instant now) {
        for (int back = depth; back >= 0; back--) {
            if (!points.containsKey(bucket(back))) {
                return Optional.of(NoTradeReason.INSUFFICIENT_HISTORY);
            }
        }
        for (int back = depth; back >= 0; back--) {
            PriceVolumeWindow.Point point = points.get(bucket(back));
            if (!point.price().isKnown() || (needsVolume && !point.volume24h().isKnown())) {
                return Optional.of(NoTradeReason.UNKNOWN_DATA);
            }
        }
        Instant lastBucketEnd = bucket(0).plus(BUCKET);
        if (Duration.between(lastBucketEnd, now).compareTo(MAX_AGE) > 0) {
            return Optional.of(NoTradeReason.STALE_DATA);
        }
        return Optional.empty();
    }

    /** The price of {@code b_(n-back)}; call only after {@link #check} found no reason. */
    BigDecimal price(int back) {
        return ((MarketValue.Known) points.get(bucket(back)).price()).value();
    }

    BigDecimal volume(int back) {
        return ((MarketValue.Known) points.get(bucket(back)).volume24h()).value();
    }

    private Instant bucket(int back) {
        return lastBucket.minus(BUCKET.multipliedBy(back));
    }

    /** True when {@code value} is KNOWN and observed at most 5 minutes before {@code now} (BR4.1, BR4.5). */
    static boolean fresh(MarketValue value, Instant now) {
        return value instanceof MarketValue.Known known
                && Duration.between(known.observedAt(), now).compareTo(MAX_AGE) <= 0;
    }
}
