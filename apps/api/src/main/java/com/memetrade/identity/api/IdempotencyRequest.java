package com.memetrade.identity.api;

/** One idempotent call: where the key lives, the key itself and the digest of the request content. */
public record IdempotencyRequest(
        IdempotentOperation operation, IdempotencyScope scope, IdempotencyKey key, String requestDigest) {}
