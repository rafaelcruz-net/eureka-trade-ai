package com.memetrade.privacy.domain.request;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/** The state of a request (functional-spec.md "Estados", BR4.5): DONE and REFUSED are final. */
public enum DataRequestStatus {
    RECEIVED,
    AWAITING_VERIFICATION,
    IN_PROGRESS,
    DONE,
    REFUSED;

    /** The open states of the unique index {@code uq_open_request_per_type} (BR4.2). */
    public static final Set<DataRequestStatus> OPEN = Set.of(RECEIVED, AWAITING_VERIFICATION, IN_PROGRESS);

    public boolean isOpen() {
        return OPEN.contains(this);
    }

    public boolean isClosed() {
        return !isOpen();
    }

    /** The state of an exact upper-case name; empty for anything else. */
    public static Optional<DataRequestStatus> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(status -> status.name().equals(value))
                .findFirst();
    }
}
