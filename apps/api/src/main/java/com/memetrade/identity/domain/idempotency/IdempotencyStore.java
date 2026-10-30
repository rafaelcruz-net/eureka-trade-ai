package com.memetrade.identity.domain.idempotency;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence port of the idempotency mechanism; implemented with PostgreSQL in the infra package. */
public interface IdempotencyStore {

    /** The record for (scope, key) that has not expired at {@code now}. */
    Optional<IdempotencyRecord> findActive(String scope, UUID key, Instant now);

    /**
     * Claims (scope, key) for a new execution: inserts an IN_PROGRESS record, or replaces an expired one. Returns
     * false when an active record already exists (in PostgreSQL a concurrent claim waits for the other transaction).
     */
    boolean tryClaim(String scope, UUID key, String requestDigest, Instant now, Instant provisionalExpiresAt);

    void complete(String scope, UUID key, int status, String body, Instant expiresAt);

    /** Drops a claim whose action failed, so nothing is kept for a request that had no effect. */
    void release(String scope, UUID key);

    /** Deletes up to {@code batchSize} expired records; returns how many were deleted. */
    int deleteExpired(Instant now, int batchSize);
}
