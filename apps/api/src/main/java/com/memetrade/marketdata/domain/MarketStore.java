package com.memetrade.marketdata.domain;

import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketSourceAdapter.TokenInfo;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.SourceStatus;
import com.memetrade.marketdata.api.TokenId;
import java.time.Instant;
import java.util.Optional;

/** Write port of the MarketData module (its own tables only). */
public interface MarketStore {

    void sourceCollected(SourceRef source, Instant collectedAt);

    /** Marks the source FAILED and keeps its last successful time and its current observations (F5 step 4). */
    void sourceFailed(SourceRef source, Instant attemptedAt);

    void upsertToken(TokenInfo token, Instant seenAt);

    /** Replaces the current observation of (token, source, field). */
    void upsertObservation(TokenId token, String sourceId, MarketField field, MarketValue value, Instant observedAt);

    /** Keeps the listing order key in step with the CREATED_AT observation (BR3.6). */
    void updateCreationTime(TokenId token, Optional<Instant> createdAt);

    Optional<SourceStatus> sourceStatus(String sourceId);
}
