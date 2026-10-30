package com.memetrade.identity.domain.idempotency;

import java.time.Instant;
import java.util.UUID;

/** Stored result of a state-changing request (entities.md IdempotencyRecord). */
public record IdempotencyRecord(
        String scope,
        UUID key,
        String requestDigest,
        State state,
        Integer responseStatus,
        String responseBody,
        Instant createdAt,
        Instant expiresAt) {

    public enum State {
        IN_PROGRESS,
        COMPLETED
    }
}
