package com.memetrade.privacy.domain.request;

import com.memetrade.identity.api.PersonDataPort.AcceptedVersion;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The Access summary (entities.md AccessSummary, BR4.8): only data of the person of the request, assembled through J1
 * to J4 and the requests of that person, never a full wallet. Kept as a snapshot when an ACCESS request is DONE with
 * the release.
 */
public record AccessSummary(
        String walletMasked,
        String provider,
        Instant accountCreatedAt,
        Optional<Instant> lastLoginAt,
        List<AcceptedVersion> acceptedVersions,
        long botCount,
        long paperOrderCount,
        long feedbackCount,
        List<RequestLine> requests) {

    public AccessSummary {
        Objects.requireNonNull(walletMasked, "walletMasked");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(accountCreatedAt, "accountCreatedAt");
        Objects.requireNonNull(lastLoginAt, "lastLoginAt");
        acceptedVersions = List.copyOf(acceptedVersions);
        requests = List.copyOf(requests);
        if (botCount < 0 || paperOrderCount < 0 || feedbackCount < 0) {
            throw new IllegalArgumentException("The counts of an Access summary are never negative");
        }
    }

    /** One request of the person in the summary: never its text. */
    public record RequestLine(DataRequestType type, DataRequestStatus status, Instant receivedAt) {
        public RequestLine {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(receivedAt, "receivedAt");
        }
    }
}
