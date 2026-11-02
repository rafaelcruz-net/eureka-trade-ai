package com.memetrade.marketdata.domain;

import com.memetrade.marketdata.api.FailureCategory;
import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketSourceAdapter.TokenInfo;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.SourceStatus;
import com.memetrade.marketdata.api.TokenId;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Test double of the MarketData store, with the same foreign-key rule as PostgreSQL for observations. */
public final class InMemoryMarketStore implements MarketStore {

    public record Stored(MarketValue value, Instant observedAt) {}

    public final Map<String, SourceState> sources = new TreeMap<>();
    public final Set<String> disabled = new HashSet<>();
    public final Map<TokenId, TokenInfo> tokens = new HashMap<>();
    public final Map<String, Stored> observations = new HashMap<>();
    public final Map<String, Stored> samples = new HashMap<>();
    public final Map<TokenId, Optional<Instant>> creation = new HashMap<>();
    public long mark;

    @Override
    public void registerSource(SourceRef source, Duration interval) {
        sources.putIfAbsent(
                source.sourceId(),
                new SourceState(
                        source, SourceStatus.State.PENDING, Optional.empty(), Optional.empty(), Optional.empty()));
        disabled.remove(source.sourceId());
    }

    @Override
    public void disableSourcesOtherThan(Collection<String> enabledIds) {
        sources.keySet().stream().filter(id -> !enabledIds.contains(id)).forEach(disabled::add);
    }

    @Override
    public Optional<SourceState> sourceState(String sourceId) {
        return Optional.ofNullable(sources.get(sourceId));
    }

    @Override
    public List<SourceStatus> sourceStatuses() {
        return sources.values().stream()
                .filter(s -> !disabled.contains(s.source().sourceId()))
                .map(SourceState::toStatus)
                .toList();
    }

    @Override
    public void updateSourceStatus(
            String sourceId,
            SourceStatus.State status,
            Optional<FailureCategory> category,
            Instant attemptedAt,
            Optional<Instant> updatedAt) {
        SourceState previous = sources.get(sourceId);
        sources.put(
                sourceId,
                new SourceState(
                        previous.source(),
                        status,
                        category,
                        updatedAt.or(previous::lastUpdatedAt),
                        Optional.of(attemptedAt)));
    }

    @Override
    public void incrementStatusMark() {
        mark++;
    }

    @Override
    public long statusMark() {
        return mark;
    }

    @Override
    public void writeCollection(CollectionWrite write) {
        for (TokenInfo token : write.tokens()) {
            upsertToken(token, write.seenAt());
            Map<MarketField, ObservedValue> fields = write.values().getOrDefault(token.token(), Map.of());
            fields.forEach((field, observed) -> {
                upsertObservation(token.token(), write.sourceId(), field, observed.value(), observed.observedAt());
                if (field != MarketField.CREATED_AT) {
                    insertSample(token.token(), write.sourceId(), field, observed.value(), observed.observedAt());
                }
            });
            ObservedValue created = fields.get(MarketField.CREATED_AT);
            updateCreationTime(
                    token.token(), created == null ? Optional.empty() : MarketCollector.creationTime(created.value()));
        }
    }

    @Override
    public void upsertToken(TokenInfo token, Instant seenAt) {
        tokens.put(token.token(), token);
    }

    @Override
    public void upsertObservation(
            TokenId token, String sourceId, MarketField field, MarketValue value, Instant observedAt) {
        requireSource(sourceId);
        observations.put(key(token, sourceId, field), new Stored(value, observedAt));
    }

    @Override
    public void insertSample(TokenId token, String sourceId, MarketField field, MarketValue value, Instant observedAt) {
        requireSource(sourceId);
        long bucket = Math.floorDiv(observedAt.getEpochSecond(), 300);
        samples.putIfAbsent(key(token, sourceId, field) + "/" + bucket, new Stored(value, observedAt));
    }

    @Override
    public void updateCreationTime(TokenId token, Optional<Instant> createdAt) {
        creation.put(token, createdAt);
    }

    public Stored observation(TokenId token, String sourceId, MarketField field) {
        return observations.get(key(token, sourceId, field));
    }

    private void requireSource(String sourceId) {
        if (!sources.containsKey(sourceId)) {
            throw new IllegalStateException("market_observation_source_id_fkey: unknown source " + sourceId);
        }
    }

    private static String key(TokenId token, String sourceId, MarketField field) {
        return token.chain() + "/" + token.mint() + "/" + sourceId + "/" + field;
    }
}
