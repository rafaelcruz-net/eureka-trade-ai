package com.memetrade.identity.api;

/** HTTP status and JSON body of an idempotent call; {@code replayed} is true when it came from the store. */
public record IdempotentResponse(int status, String body, boolean replayed) {

    public static IdempotentResponse fresh(int status, String body) {
        return new IdempotentResponse(status, body, false);
    }
}
