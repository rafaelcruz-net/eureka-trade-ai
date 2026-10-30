package com.memetrade.marketdata.api;

import java.time.Instant;
import java.util.List;

/**
 * Port of a market data source (contract C19). U1 ships only the simulated implementation ({@code simulated =
 * true}); real adapters arrive in U4 behind this same interface, after the sources are approved (G0, C-06).
 *
 * <p>Rules: a field the source did not provide is {@link MarketValue.Unknown} with {@code NOT_PROVIDED}, never zero;
 * an informed zero is {@link MarketValue.Known} with value 0; tests use recorded fixtures and never the network.
 */
public interface MarketSourceAdapter {

    SourceRef source();

    /**
     * Fetches the current tokens and their observations.
     *
     * @throws RuntimeException when the source fails; the collector marks it FAILED and keeps the last values
     */
    FetchResult fetch(FetchRequest request);

    record FetchRequest(Instant requestedAt) {}

    record FetchResult(List<TokenInfo> tokens, List<Observation> observations) {}

    record TokenInfo(TokenId token, String symbol, String name, int decimals) {}

    record Observation(TokenId token, MarketField field, MarketValue value) {}
}
