package com.memetrade.identity.domain.idempotency;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Test double of the store: atomic claim per (scope, key), like the PostgreSQL primary key. */
public final class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Map<String, IdempotencyRecord> records = new ConcurrentHashMap<>();

    @Override
    public Optional<IdempotencyRecord> findActive(String scope, UUID key, Instant now) {
        return Optional.ofNullable(records.get(id(scope, key)))
                .filter(r -> r.expiresAt().isAfter(now));
    }

    @Override
    public synchronized boolean tryClaim(
            String scope, UUID key, String requestDigest, Instant now, Instant provisionalExpiresAt) {
        IdempotencyRecord existing = records.get(id(scope, key));
        if (existing != null && existing.expiresAt().isAfter(now)) {
            return false;
        }
        records.put(
                id(scope, key),
                new IdempotencyRecord(
                        scope,
                        key,
                        requestDigest,
                        IdempotencyRecord.State.IN_PROGRESS,
                        null,
                        null,
                        now,
                        provisionalExpiresAt));
        return true;
    }

    @Override
    public synchronized void complete(String scope, UUID key, int status, String body, Instant expiresAt) {
        IdempotencyRecord claimed = records.get(id(scope, key));
        records.put(
                id(scope, key),
                new IdempotencyRecord(
                        scope,
                        key,
                        claimed.requestDigest(),
                        IdempotencyRecord.State.COMPLETED,
                        status,
                        body,
                        claimed.createdAt(),
                        expiresAt));
    }

    @Override
    public void release(String scope, UUID key) {
        records.remove(id(scope, key));
    }

    @Override
    public int deleteExpired(Instant now, int batchSize) {
        int[] deleted = {0};
        records.entrySet().removeIf(entry -> {
            if (deleted[0] < batchSize && !entry.getValue().expiresAt().isAfter(now)) {
                deleted[0]++;
                return true;
            }
            return false;
        });
        return deleted[0];
    }

    public int size() {
        return records.size();
    }

    public Optional<IdempotencyRecord> raw(String scope, UUID key) {
        return Optional.ofNullable(records.get(id(scope, key)));
    }

    private static String id(String scope, UUID key) {
        return scope + "|" + key;
    }
}
