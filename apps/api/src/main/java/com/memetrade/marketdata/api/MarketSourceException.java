package com.memetrade.marketdata.api;

import java.util.Objects;

/**
 * Failure of a {@link MarketSourceAdapter} call with its category and whether a new attempt makes sense
 * (reliability-design.md): time-outs, network errors and 5xx/429 answers are retried; an invalid payload and the other
 * 4xx answers are not. Any other exception thrown by an adapter counts as a retryable {@code ERROR_RESPONSE}.
 */
public class MarketSourceException extends RuntimeException {

    private final FailureCategory category;
    private final boolean retryable;

    public MarketSourceException(FailureCategory category, boolean retryable, String message) {
        super(message);
        this.category = Objects.requireNonNull(category, "category");
        this.retryable = retryable;
    }

    /** An unusable answer: never retried (closed list of reliability-design.md). */
    public static MarketSourceException invalidPayload(String message) {
        return new MarketSourceException(FailureCategory.INVALID_PAYLOAD, false, message);
    }

    /** An HTTP-like answer of the source: 5xx and 429 are retried, other 4xx are not. */
    public static MarketSourceException errorResponse(int status) {
        boolean retryable = status >= 500 || status == 429;
        return new MarketSourceException(FailureCategory.ERROR_RESPONSE, retryable, "Source answered " + status);
    }

    public FailureCategory category() {
        return category;
    }

    public boolean retryable() {
        return retryable;
    }
}
