package com.memetrade.marketdata.domain;

import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketSourceAdapter.TokenInfo;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.SourceStatus;
import com.memetrade.marketdata.api.TokenId;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Test double of the MarketData store. */
public final class InMemoryMarketStore implements MarketStore {

    public record Stored(MarketValue value, Instant observedAt) {}

    public final Map<String, SourceStatus> sources = new HashMap<>();
    public final Map<TokenId, TokenInfo> tokens = new HashMap<>();
    public final Map<String, Stored> observations = new HashMap<>();
    public final Map<TokenId, Optional<Instant>> creation = new HashMap<>();

    @Override
    public void sourceCollected(SourceRef source, Instant collectedAt) {
        sources.put(source.sourceId(), new SourceStatus(source, SourceStatus.State.OK, Optional.of(collectedAt)));
    }

    @Override
    public void sourceFailed(SourceRef source, Instant attemptedAt) {
        Optional<Instant> last =
                Optional.ofNullable(sources.get(source.sourceId())).flatMap(SourceStatus::lastUpdatedAt);
        sources.put(source.sourceId(), new SourceStatus(source, SourceStatus.State.FAILED, last));
    }

    @Override
    public void upsertToken(TokenInfo token, Instant seenAt) {
        tokens.put(token.token(), token);
    }

    @Override
    public void upsertObservation(
            TokenId token, String sourceId, MarketField field, MarketValue value, Instant observedAt) {
        // Same rule as the market_observation -> data_source foreign key in PostgreSQL.
        if (!sources.containsKey(sourceId)) {
            throw new IllegalStateException("market_observation_source_id_fkey: unknown source " + sourceId);
        }
        observations.put(key(token, field), new Stored(value, observedAt));
    }

    @Override
    public void updateCreationTime(TokenId token, Optional<Instant> createdAt) {
        creation.put(token, createdAt);
    }

    @Override
    public Optional<SourceStatus> sourceStatus(String sourceId) {
        return Optional.ofNullable(sources.get(sourceId));
    }

    public Stored observation(TokenId token, MarketField field) {
        return observations.get(key(token, field));
    }

    private static String key(TokenId token, MarketField field) {
        return token.chain() + "/" + token.mint() + "/" + field;
    }
}
