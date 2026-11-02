package com.memetrade.marketdata.domain;

import com.memetrade.marketdata.api.FailureCategory;
import com.memetrade.marketdata.api.SourceStatus;
import com.memetrade.marketdata.domain.ObservationValidator.DiscardReason;
import java.util.Map;
import java.util.Optional;

/** What one collection of a source did, for the metrics and the logs (observability-design.md). */
public record CollectionOutcome(
        String sourceId,
        SourceStatus.State previous,
        SourceStatus.State status,
        Optional<FailureCategory> category,
        int retries,
        Map<DiscardReason, Integer> discarded,
        int tokens,
        Optional<String> exceptionClass) {

    public CollectionOutcome {
        discarded = Map.copyOf(discarded);
    }

    public boolean changed() {
        return previous != status;
    }
}
