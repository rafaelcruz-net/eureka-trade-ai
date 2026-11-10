package com.memetrade.privacy.domain.request;

/**
 * BR4.2, NFR3.8: the person already has an open request of that type; raised by the store when the unique index
 * {@code uq_open_request_per_type} refuses the insert (also under a race). Carries no value of the request.
 */
public class OpenRequestConflictException extends RuntimeException {

    public OpenRequestConflictException() {
        super("An open request of the same type already exists for the person");
    }

    public OpenRequestConflictException(Throwable cause) {
        super("An open request of the same type already exists for the person", cause);
    }
}
