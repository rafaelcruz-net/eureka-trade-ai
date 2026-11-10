package com.memetrade.privacy.domain.request;

import java.util.Arrays;
import java.util.Optional;

/** The four types of a data subject request (entities.md DataSubjectRequest.type, BR4.1). */
public enum DataRequestType {
    ACCESS,
    CORRECTION,
    DELETION,
    OTHER;

    /** The type of an exact upper-case name; empty for anything else (never echoed in an error). */
    public static Optional<DataRequestType> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(type -> type.name().equals(value)).findFirst();
    }

    /** BR4.11: only these types confirm the identity of an EMAIL request by signature. */
    public boolean needsVerification() {
        return this == ACCESS || this == DELETION;
    }
}
