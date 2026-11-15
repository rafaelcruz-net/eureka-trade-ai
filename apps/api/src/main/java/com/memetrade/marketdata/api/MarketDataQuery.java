package com.memetrade.marketdata.api;

import java.time.Duration;
import java.util.List;

/**
 * Public read interface of MarketData (contract C11), used by U4 and later by U5 to U8.
 *
 * <p>Rules: missing values are {@link MarketValue.Unknown}, never zero nor {@code null}; {@code stale} is true when the
 * age passes the limit of the field (5 minutes for PRICE, LIQUIDITY and VOLUME_24H, 2 hours for HOLDERS and CREATED_AT
 * since U2) and each consumer applies its own freshness limit; a synchronous call without retry, where a technical
 * failure becomes {@code Unknown(SOURCE_UNAVAILABLE)}. A single source is derived from {@link ResolvedField}: one
 * origin and no divergence (U2 BR1.7).
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

    /**
     * 261113-trading-real-bot U2 (BR1.5, contract M4): REAL when every configured source is real, SIMULATED as soon as
     * one is simulated (and, fail-closed, when no source is known). The production implementation answers from the
     * configuration; this default derives it from {@link #sourceStatuses()} for the doubles of other modules. The
     * {@code quote(chain, mint)} of M4 is {@link #referencePrice(TokenId)}, whose {@code observedAt} carries the time of
     * the fetch; the 60 s rule of REAL orders is applied by U3.
     */
    default SourceKind activeSourceKind() {
        List<SourceStatus> sources = sourceStatuses();
        boolean simulated =
                sources.isEmpty() || sources.stream().anyMatch(s -> s.source().simulated());
        return simulated ? SourceKind.SIMULATED : SourceKind.REAL;
    }

    /** U2 (BR1.5, BR1.8): the attribution of the active real source, empty without one. */
    default java.util.Optional<Attribution> attribution() {
        return java.util.Optional.empty();
    }

    /**
     * 261113-trading-real-bot U3 (M4, Q3 of the NFR Design): the displayed PRICE of every token of the set, with the
     * same {@code observedAt} and the same unknown rule as {@link #referencePrice(TokenId)}; every token of the set has
     * an entry. The production implementation reads all of them in one query; this default reads them one by one for
     * the doubles of other modules.
     */
    default java.util.Map<TokenId, MarketValue> referencePrices(java.util.Set<TokenId> tokens) {
        java.util.Map<TokenId, MarketValue> prices = new java.util.LinkedHashMap<>();
        tokens.forEach(token -> prices.put(token, referencePrice(token)));
        return prices;
    }

    /**
     * 261113-trading-real-bot U3 (BR5.2): {@link #decimals(TokenId)} of every token of the set (one query in
     * production). A token never observed is unknown, never zero.
     */
    default java.util.Map<TokenId, MarketValue> decimalsOf(java.util.Set<TokenId> tokens) {
        java.util.Map<TokenId, MarketValue> decimals = new java.util.LinkedHashMap<>();
        tokens.forEach(token -> decimals.put(token, decimals(token)));
        return decimals;
    }

    /**
     * 261113-trading-real-bot U3 (BR3.1): the displayed 24 h VOLUME of the token, with its {@code observedAt}. The
     * default is unknown (fail-closed: a REAL buy needs a known volume); the production implementation reads it.
     */
    default MarketValue volume24h(TokenId token) {
        return new MarketValue.Unknown(UnknownReason.NOT_PROVIDED, java.util.Optional.empty());
    }
}
