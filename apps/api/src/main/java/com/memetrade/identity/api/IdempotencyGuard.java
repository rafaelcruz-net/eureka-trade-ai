package com.memetrade.identity.api;

import java.util.function.Supplier;

/**
 * Common idempotency mechanism of the API (contract-summary.md "Idempotência"); born in U1 in the identity module
 * and used by later Units through this interface.
 */
public interface IdempotencyGuard {

    /**
     * Runs {@code action} at most once per (scope, key) while the stored result is retained. Same key and same
     * content return the stored response; same key with different content fails with {@code 409
     * IDEMPOTENCY_CONFLICT}. Must run inside the caller's transaction boundary so the action and the record commit
     * together.
     */
    IdempotentResponse execute(IdempotencyRequest request, Supplier<IdempotentOutcome> action);
}
