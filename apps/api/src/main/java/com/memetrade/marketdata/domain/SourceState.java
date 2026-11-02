package com.memetrade.marketdata.domain;

import com.memetrade.marketdata.api.FailureCategory;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.SourceStatus;
import java.time.Instant;
import java.util.Optional;

/** Stored state of a source (entities.md, DataSource). */
public record SourceState(
        SourceRef source,
        SourceStatus.State status,
        Optional<FailureCategory> failureCategory,
        Optional<Instant> lastUpdatedAt,
        Optional<Instant> lastAttemptAt) {

    public SourceStatus toStatus() {
        return new SourceStatus(source, status, lastUpdatedAt);
    }
}
