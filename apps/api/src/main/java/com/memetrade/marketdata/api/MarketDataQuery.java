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

    /**
     * U7 addition to C11 (BR1.10): the number of decimal places of the token's mint, read from {@code token.decimals}.
     * {@link MarketValue.Known} carries an integral value (scale 0, 0 to 18) with the source that last observed the
     * token; a token never observed, or a technical failure, is {@link MarketValue.Unknown}, never zero. Decimals never
     * go stale. The contract table writes it as {@code MarketValue<Integer>}; the existing {@link MarketValue} is not
     * generic, so the integer travels as {@link java.math.BigDecimal} and the consumer reads it with
     * {@code intValueExact()}.
     */
    MarketValue decimals(TokenId token);

    /** Price and 24 h volume per 5-minute bucket over the last {@code length} (at most 24 hours); gaps are UNKNOWN. */
    PriceVolumeWindow window(TokenId token, Duration length);

    /** Status of every enabled source, with its last success. */
    List<SourceStatus> sourceStatuses();
}
