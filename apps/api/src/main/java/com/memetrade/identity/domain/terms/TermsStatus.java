package com.memetrade.identity.domain.terms;

import java.time.Instant;
import java.util.Objects;

/**
 * u3-reacceptance (entities.md TermsStatus, L2): the situation of a person before the effective current version of the
 * terms. {@code acceptedVersion} and {@code acceptedAt} are those of the most recent acceptance; {@code upToDate} says
 * whether the person has an acceptance of {@code currentVersion}, the same decision the reacceptance lock takes
 * (BR3.1), so the Account screen never offers the new acceptance to a person the lock lets through.
 */
public record TermsStatus(String acceptedVersion, String currentVersion, Instant acceptedAt, boolean upToDate) {

    public TermsStatus {
        Objects.requireNonNull(acceptedVersion, "acceptedVersion");
        Objects.requireNonNull(currentVersion, "currentVersion");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
    }
}
