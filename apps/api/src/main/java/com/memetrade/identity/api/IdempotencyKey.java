package com.memetrade.identity.api;

import com.memetrade.shared.error.ApiException;
import java.util.Objects;
import java.util.UUID;

/** Value of the {@code Idempotency-Key} header (a UUID). */
public record IdempotencyKey(UUID value) {

    public static final String HEADER = "Idempotency-Key";

    public IdempotencyKey {
        Objects.requireNonNull(value, "value");
    }

    /** Parses the header; a missing or non-UUID value is {@code 400 VALIDATION_FAILED} on {@code Idempotency-Key}. */
    public static IdempotencyKey parse(String header) {
        if (header == null || header.isBlank()) {
            throw ApiException.validation(HEADER, "Header is required for state-changing requests.");
        }
        try {
            UUID parsed = UUID.fromString(header.trim());
            if (!parsed.toString().equalsIgnoreCase(header.trim())) {
                throw ApiException.validation(HEADER, "Header must be a UUID.");
            }
            return new IdempotencyKey(parsed);
        } catch (IllegalArgumentException notUuid) {
            throw ApiException.validation(HEADER, "Header must be a UUID.");
        }
    }
}
