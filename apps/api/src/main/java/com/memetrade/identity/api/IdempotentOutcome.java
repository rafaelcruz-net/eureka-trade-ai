package com.memetrade.identity.api;

import java.time.Instant;

/**
 * Result of the action guarded by an idempotency key. {@code resultExpiresAt} is used by operations whose
 * response is kept only while the result is valid (the login challenge); {@code null} means default retention.
 */
public record IdempotentOutcome(IdempotentResponse response, Instant resultExpiresAt) {

    public static IdempotentOutcome of(IdempotentResponse response) {
        return new IdempotentOutcome(response, null);
    }
}
