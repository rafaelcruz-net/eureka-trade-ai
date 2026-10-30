package com.memetrade.marketdata.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Public read interface of MarketData used by the Scanner (the Scanner never reads MarketData tables). Tokens come in
 * the listing order of BR3.6: creation time known first, newest first, ties by mint; unknown creation time last.
 */
public interface MarketListingQuery {

    /** One page after {@code after} (exclusive), among tokens first seen at or before {@code cutoff}. */
    ListingPage page(Optional<ListingPosition> after, Instant cutoff, int limit);

    /** Number of tokens first seen at or before {@code cutoff}. */
    long count(Instant cutoff);

    List<SourceStatus> sources();

    /** Keyset position: group 0 = creation known, 1 = unknown; sortKey = negated creation epoch micros (0 if unknown). */
    record ListingPosition(int group, long sortKey, String mint, String chain) {}

    /** A token with its current value per field; stale is computed at read time with the injectable clock. */
    record ListedToken(
            TokenId token,
            String symbol,
            String name,
            ListingPosition position,
            Map<MarketField, MarketValue> values) {}

    record ListingPage(List<ListedToken> tokens, boolean hasMore) {}
}
