package com.memetrade.marketdata.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Port of a market data source (contract C19). The simulated implementations ({@code simulated = true}) and, since
 * 261113-trading-real-bot U2, the real CoinGecko adapter of the worker live behind it.
 *
 * <p>Rules: a field the source did not provide is {@link MarketValue.Unknown} with {@code NOT_PROVIDED}, never zero;
 * an informed zero is {@link MarketValue.Known} with value 0; tests use recorded fixtures and never the network.
 */
public interface MarketSourceAdapter {

    SourceRef source();

    /**
     * Fetches the current tokens and their observations.
     *
     * @throws RuntimeException when the source fails; the collector marks it FAILED (or, for a
     *     {@linkplain #tolerantFailure() tolerant} source, DEGRADED on the first round without a price) and keeps the
     *     last values
     */
    FetchResult fetch(FetchRequest request);

    /**
     * U2 (BR1.9, infrastructure-specification.md "Tolerância de falha"): true when one round without any price is
     * tolerated as DEGRADED and only the second one in a row is FAILED. The simulated sources keep {@code false}.
     */
    default boolean tolerantFailure() {
        return false;
    }

    /**
     * @param watched tokens observed by other modules ({@link WatchedTokens}), kept in the active set of a real source
     *     (BR1.1); empty for the simulated sources
     */
    record FetchRequest(Instant requestedAt, Set<TokenId> watched) {

        public FetchRequest {
            Objects.requireNonNull(requestedAt, "requestedAt");
            watched = Set.copyOf(watched);
        }

        public FetchRequest(Instant requestedAt) {
            this(requestedAt, Set.of());
        }
    }

    /**
     * @param round present for a source that sends only the fields it renewed in this round (U2): the collector then
     *     writes only those fields, keeps the earlier observations of the others, and decides the state from the
     *     summary of the price round (BR1.9)
     */
    record FetchResult(List<TokenInfo> tokens, List<Observation> observations, Optional<RoundSummary> round) {

        public FetchResult {
            Objects.requireNonNull(round, "round");
        }

        public FetchResult(List<TokenInfo> tokens, List<Observation> observations) {
            this(tokens, observations, Optional.empty());
        }
    }

    /**
     * What the price round of a real source achieved (BR1.9).
     *
     * @param activeTokens tokens of the active set at the end of the round
     * @param pricedTokens tokens whose PRICE was renewed as KNOWN in this round
     * @param cut why the round stopped early: {@code TIMEOUT} when the deadline cut it, {@code ERROR_RESPONSE} after a
     *     429, a 5xx, an open circuit, the quota, the budget or the conservative mode; empty when it ran to the end
     */
    record RoundSummary(int activeTokens, int pricedTokens, Optional<FailureCategory> cut) {

        public RoundSummary {
            Objects.requireNonNull(cut, "cut");
            if (activeTokens < 0 || pricedTokens < 0) {
                throw new IllegalArgumentException("Counts are never negative");
            }
        }

        /** No price at all in this round (with or without active tokens). */
        public boolean noPrice() {
            return pricedTokens == 0;
        }

        /** Every active token got its price and nothing cut the round. */
        public boolean complete() {
            return activeTokens > 0 && pricedTokens >= activeTokens && cut.isEmpty();
        }
    }

    record TokenInfo(TokenId token, String symbol, String name, int decimals) {}

    record Observation(TokenId token, MarketField field, MarketValue value) {}
}
