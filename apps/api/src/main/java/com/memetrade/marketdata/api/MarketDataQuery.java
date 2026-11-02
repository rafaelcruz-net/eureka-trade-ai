package com.memetrade.marketdata.api;

import java.time.Duration;
import java.util.List;

/**
 * Public read interface of MarketData (contract C11), used by U4 and later by U5 to U8.
 *
 * <p>Rules: missing values are {@link MarketValue.Unknown}, never zero nor {@code null}; {@code stale} is true when the
 * age passes 5 minutes and each consumer applies its own freshness limit; a synchronous call without retry, where a
 * technical failure becomes {@code Unknown(SOURCE_UNAVAILABLE)}.
 */
public interface MarketDataQuery {

    /** The displayed PRICE of the token (most recent KNOWN value among the sources that are not FAILED). */
    MarketValue referencePrice(TokenId token);

    MarketValue liquidity(TokenId token);

    /** Price and 24 h volume per 5-minute bucket over the last {@code length} (at most 24 hours); gaps are UNKNOWN. */
    PriceVolumeWindow window(TokenId token, Duration length);

    /** Status of every enabled source, with its last success. */
    List<SourceStatus> sourceStatuses();
}
