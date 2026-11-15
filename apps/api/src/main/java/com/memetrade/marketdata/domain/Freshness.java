package com.memetrade.marketdata.domain;

import com.memetrade.marketdata.api.MarketField;
import java.time.Duration;
import java.time.Instant;

/**
 * BR3.4: a value is stale when its age is STRICTLY above its limit, measured with the injectable clock.
 *
 * <p>261113-trading-real-bot U2 (BR1.4, entities.md FieldFreshness): the limit is per field. PRICE, LIQUIDITY and
 * VOLUME_24H keep 5 minutes; HOLDERS and CREATED_AT get 2 hours, because the real source renews them every 60 minutes.
 * The 60 s rule of REAL orders is not here: the barrier (U3) applies it to {@code observedAt} of the price fields.
 */
public final class Freshness {

    /** The limit of the price fields (and of the callers that do not name a field). */
    public static final Duration STALE_AFTER = Duration.ofMinutes(5);

    /** U2: the limit of HOLDERS and CREATED_AT. */
    public static final Duration DETAIL_STALE_AFTER = Duration.ofHours(2);

    private Freshness() {}

    /** The 5-minute rule of the price fields. */
    public static boolean isStale(Instant observedAt, Instant now) {
        return Duration.between(observedAt, now).compareTo(STALE_AFTER) > 0;
    }

    /** The rule of {@code field}: 5 minutes for the price fields, 2 hours for HOLDERS and CREATED_AT. */
    public static boolean isStale(MarketField field, Instant observedAt, Instant now) {
        return Duration.between(observedAt, now).compareTo(staleAfter(field)) > 0;
    }

    public static Duration staleAfter(MarketField field) {
        return switch (field) {
            case PRICE, LIQUIDITY, VOLUME_24H -> STALE_AFTER;
            case HOLDERS, CREATED_AT -> DETAIL_STALE_AFTER;
        };
    }
}
