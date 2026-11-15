package com.memetrade.botwallet.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Release of the REAL for one person on one network (BR7.1): it takes effect 24 hours after the request. */
public record RealRelease(
        UUID releaseId,
        UUID userId,
        Network network,
        UUID requestedBy,
        Instant requestedAt,
        Instant effectiveAt,
        Instant revokedAt) {

    public static final Duration QUARANTINE = Duration.ofHours(24);

    /** In the U1 a release is created only by the seed of the test and local profiles. */
    public static RealRelease seeded(UUID userId, Network network, UUID requestedBy, Instant requestedAt) {
        return new RealRelease(
                UUID.randomUUID(), userId, network, requestedBy, requestedAt, requestedAt.plus(QUARANTINE), null);
    }

    public RealRelease revokedAt(Instant when) {
        return new RealRelease(releaseId, userId, network, requestedBy, requestedAt, effectiveAt, when);
    }

    public boolean isEffectiveAt(Network on, Instant now) {
        return network == on && !now.isBefore(effectiveAt) && (revokedAt == null || now.isBefore(revokedAt));
    }
}
