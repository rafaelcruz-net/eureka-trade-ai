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
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Write port of the MarketData module (its own tables only). */
public interface MarketStore {

    /**
     * Registers a configured source: a new source starts PENDING; an existing one keeps its state (a restart never
     * rewrites it, NFR10.6) and only gets its name, flag and interval refreshed and is enabled.
     */
    void registerSource(SourceRef source, Duration interval);

    /** Disables every source not in {@code enabledIds} (no longer configured): it no longer counts anywhere. */
    void disableSourcesOtherThan(Collection<String> enabledIds);

    Optional<SourceState> sourceState(String sourceId);

    /** Status of every enabled source, ordered by id. */
    List<SourceStatus> sourceStatuses();

    /**
     * Sets the state after a collection; {@code lastAttemptAt} always changes, {@code lastUpdatedAt} only when present
     * (OK or DEGRADED, BR1.5).
     */
    void updateSourceStatus(
            String sourceId,
            SourceStatus.State status,
            Optional<FailureCategory> category,
            Instant attemptedAt,
            Optional<Instant> updatedAt);

    /** C18: {@code mark = mark + 1}, in the transaction that changed a state (BR1.11). */
    void incrementStatusMark();

    long statusMark();

    /**
     * Writes one collection in batches: tokens (first seen now when new), the current value of every field (replaced),
     * one sample per 5-minute bucket (the first observation of the bucket stays) and the creation times.
     */
    void writeCollection(CollectionWrite write);

    void upsertToken(TokenInfo token, Instant seenAt);

    /** Replaces the current observation of (token, source, field). */
    void upsertObservation(TokenId token, String sourceId, MarketField field, MarketValue value, Instant observedAt);

    /** Adds a history sample unless the bucket of {@code observedAt} already has one. */
    void insertSample(TokenId token, String sourceId, MarketField field, MarketValue value, Instant observedAt);

    /** Keeps {@code token_created_at} and the U1 order columns in step with the CREATED_AT observation. */
    void updateCreationTime(TokenId token, Optional<Instant> createdAt);

    /** Everything one collection writes. */
    record CollectionWrite(
            String sourceId,
            Instant seenAt,
            List<TokenInfo> tokens,
            Map<TokenId, Map<MarketField, ObservedValue>> values) {}

    /** A field value with the time the source observed it (the collection time for an UNKNOWN value). */
    record ObservedValue(MarketValue value, Instant observedAt) {}
}
