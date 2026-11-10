package com.memetrade.privacy.domain.request;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The signature verification of an EMAIL request linked to an account (entities.md IdentityVerification; BR4.11 to
 * BR4.13). The message is not a secret (the administrator sends it to the contact); the signature is never part of this
 * value nor stored anywhere.
 */
public record IdentityVerification(
        java.util.UUID requestId,
        String message,
        Instant issuedAt,
        Instant expiresAt,
        VerificationState state,
        Optional<Instant> confirmedAt) {

    public IdentityVerification {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(confirmedAt, "confirmedAt");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("A verification expires after it was issued");
        }
        if ((state == VerificationState.CONFIRMED) != confirmedAt.isPresent()) {
            throw new IllegalArgumentException("Only a CONFIRMED verification has its confirmation instant");
        }
    }

    public IdentityVerification confirmed(Instant at) {
        return new IdentityVerification(
                requestId, message, issuedAt, expiresAt, VerificationState.CONFIRMED, Optional.of(at));
    }

    public IdentityVerification expired() {
        return new IdentityVerification(
                requestId, message, issuedAt, expiresAt, VerificationState.EXPIRED, Optional.empty());
    }

    /** Past its validity at {@code now} (the instant itself included). */
    public boolean isExpiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }

    @Override
    public String toString() {
        return "IdentityVerification[requestId=" + requestId + ", state=" + state + ", expiresAt=" + expiresAt + "]";
    }
}
