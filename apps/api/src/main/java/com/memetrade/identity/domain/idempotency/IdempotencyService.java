package com.memetrade.identity.domain.idempotency;

import com.memetrade.identity.api.IdempotencyGuard;
import com.memetrade.identity.api.IdempotencyRequest;
import com.memetrade.identity.api.IdempotentOperation;
import com.memetrade.identity.api.IdempotentOutcome;
import com.memetrade.identity.api.IdempotentResponse;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Deduplication by idempotency key (BR1.10, NFR4.1). The caller's transaction makes the claim, the action and the
 * stored response commit together; in PostgreSQL a concurrent claim of the same (scope, key) waits for that commit
 * and then replays the stored response, so two simultaneous requests produce a single effect.
 */
public class IdempotencyService implements IdempotencyGuard {

    private static final Pattern TOKEN_FIELD = Pattern.compile("\"(accessToken|refreshToken)\"\\s*:");

    private final IdempotencyStore store;
    private final Clock clock;
    private final Duration defaultRetention;

    public IdempotencyService(IdempotencyStore store, Clock clock, Duration defaultRetention) {
        this.store = store;
        this.clock = clock;
        this.defaultRetention = defaultRetention;
    }

    @Override
    public IdempotentResponse execute(IdempotencyRequest request, Supplier<IdempotentOutcome> action) {
        if (request.operation().retention() == IdempotentOperation.Retention.NOT_STORED) {
            return action.get().response();
        }
        Instant now = clock.instant();
        return storedResult(request, now).orElseGet(() -> runAndStore(request, action, now));
    }

    /** The replayable stored result, or empty when this call claimed the key and must run the action. */
    private java.util.Optional<IdempotentResponse> storedResult(IdempotencyRequest request, Instant now) {
        String scope = request.scope().value();
        UUID key = request.key().value();
        var existing = store.findActive(scope, key, now);
        if (existing.isPresent()) {
            return java.util.Optional.of(replay(existing.get(), request));
        }
        if (store.tryClaim(scope, key, request.requestDigest(), now, now.plus(defaultRetention))) {
            return java.util.Optional.empty();
        }
        IdempotencyRecord winner = store.findActive(scope, key, now).orElseThrow(IdempotencyService::inProgress);
        return java.util.Optional.of(replay(winner, request));
    }

    public int purgeExpired(int batchSize) {
        return store.deleteExpired(clock.instant(), batchSize);
    }

    private IdempotentResponse runAndStore(
            IdempotencyRequest request, Supplier<IdempotentOutcome> action, Instant now) {
        String scope = request.scope().value();
        UUID key = request.key().value();
        try {
            IdempotentOutcome outcome = action.get();
            IdempotentResponse response = outcome.response();
            ensureNoTokens(response.body());
            store.complete(scope, key, response.status(), response.body(), expiresAt(request, outcome, now));
            return response;
        } catch (RuntimeException failure) {
            store.release(scope, key);
            throw failure;
        }
    }

    private Instant expiresAt(IdempotencyRequest request, IdempotentOutcome outcome, Instant now) {
        boolean untilResultExpires =
                request.operation().retention() == IdempotentOperation.Retention.UNTIL_RESULT_EXPIRES;
        if (untilResultExpires && outcome.resultExpiresAt() != null) {
            return outcome.resultExpiresAt();
        }
        return now.plus(defaultRetention);
    }

    private static IdempotentResponse replay(IdempotencyRecord record, IdempotencyRequest request) {
        if (!record.requestDigest().equals(request.requestDigest())) {
            throw conflict("The same Idempotency-Key was used with different content.", "DIFFERENT_CONTENT");
        }
        if (record.state() != IdempotencyRecord.State.COMPLETED) {
            throw inProgress();
        }
        return new IdempotentResponse(record.responseStatus(), record.responseBody(), true);
    }

    private static ApiException inProgress() {
        return conflict("A request with this Idempotency-Key is still being processed.", "IN_PROGRESS");
    }

    private static ApiException conflict(String detail, String reason) {
        return new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT, detail, List.of(), reason, Map.of());
    }

    private static void ensureNoTokens(String body) {
        if (body != null && TOKEN_FIELD.matcher(body).find()) {
            throw new IllegalStateException("Idempotent responses must never store access or refresh tokens");
        }
    }
}
