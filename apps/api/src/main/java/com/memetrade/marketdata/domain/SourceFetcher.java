package com.memetrade.marketdata.domain;

import com.memetrade.marketdata.api.FailureCategory;
import com.memetrade.marketdata.api.MarketSourceAdapter;
import com.memetrade.marketdata.api.MarketSourceAdapter.FetchRequest;
import com.memetrade.marketdata.api.MarketSourceAdapter.FetchResult;
import java.util.Optional;

/**
 * Port that calls a source with a time limit and new attempts (F1 step 2, reliability-design.md); implemented in infra
 * with Resilience4j. It never throws for a source failure: it answers {@link Fetched} or {@link Failed}.
 */
@FunctionalInterface
public interface SourceFetcher {

    FetchOutcome fetch(MarketSourceAdapter adapter, FetchRequest request);

    sealed interface FetchOutcome permits Fetched, Failed {
        int retries();
    }

    /** The source answered, possibly after {@code retries} new attempts caused by {@code retryCause}. */
    record Fetched(FetchResult result, int retries, Optional<FailureCategory> retryCause) implements FetchOutcome {}

    /** The source failed after the attempts; only the category and the exception class are kept (NFR9.6). */
    record Failed(FailureCategory category, String exceptionClass, int retries) implements FetchOutcome {}
}
