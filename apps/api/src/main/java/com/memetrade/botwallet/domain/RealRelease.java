package com.memetrade.botwallet.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Release of the REAL for one person on one network (BR7.1 of the U1). 261113-trading-real-bot U5 (BR1.1 to BR1.6): it
 * starts in {@code QUARANTINE} with {@code effectiveAt} 24 hours after the request; the gates job turns it {@code ACTIVE}
 * or {@code BLOCKED} with the first red gate; a revocation makes it {@code REVOKED}, which is final. Only an
 * {@code ACTIVE} release on the network of the environment is in force.
 */
public record RealRelease(
        UUID releaseId,
        UUID userId,
        Network network,
        UUID requestedBy,
        Instant requestedAt,
        Instant effectiveAt,
        Instant revokedAt,
        ReleaseState state,
        BlockedReason blockedReason,
        Instant gatesCheckedAt,
        UUID revokedBy) {

    public static final Duration QUARANTINE = Duration.ofHours(24);

    public RealRelease {
        Objects.requireNonNull(releaseId, "releaseId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(state, "state");
    }

    /**
     * The row as the U1 wrote it, without the columns of the U5: revoked when {@code revokedAt} is set, otherwise in
     * quarantine (the migration 0021 backfills the rows that already passed their 24 hours as ACTIVE).
     */
    public RealRelease(
            UUID releaseId,
            UUID userId,
            Network network,
            UUID requestedBy,
            Instant requestedAt,
            Instant effectiveAt,
            Instant revokedAt) {
        this(
                releaseId,
                userId,
                network,
                requestedBy,
                requestedAt,
                effectiveAt,
                revokedAt,
                revokedAt == null ? ReleaseState.QUARANTINE : ReleaseState.REVOKED,
                null,
                null,
                null);
    }

    /** BR1.2: a new release in quarantine, in force only after its 24 hours and green gates. */
    public static RealRelease requested(UUID userId, Network network, UUID requestedBy, Instant requestedAt) {
        return new RealRelease(
                UUID.randomUUID(),
                userId,
                network,
                requestedBy,
                requestedAt,
                requestedAt.plus(QUARANTINE),
                null,
                ReleaseState.QUARANTINE,
                null,
                null,
                null);
    }

    /** In the U1 a release was created only by the seed of the test and local profiles (kept for those callers). */
    public static RealRelease seeded(UUID userId, Network network, UUID requestedBy, Instant requestedAt) {
        return requested(userId, network, requestedBy, requestedAt);
    }

    public RealRelease revokedAt(Instant when) {
        return revoked(when, null);
    }

    /** BR1.6: revoked at {@code when} by {@code by}; the state is final. */
    public RealRelease revoked(Instant when, UUID by) {
        return new RealRelease(
                releaseId,
                userId,
                network,
                requestedBy,
                requestedAt,
                effectiveAt,
                when,
                ReleaseState.REVOKED,
                blockedReason,
                gatesCheckedAt,
                by);
    }

    /** BR1.1: in force only when ACTIVE on the network of the environment and never revoked. */
    public boolean isEffectiveAt(Network on, Instant now) {
        return network == on && state == ReleaseState.ACTIVE && revokedAt == null;
    }

    /** A release still counts (one per person and network, BR1.2; the pilot of BR1.3) until it is revoked. */
    public boolean notRevoked() {
        return state != ReleaseState.REVOKED && revokedAt == null;
    }

    /** BR1.4: the quarantine passed and the gates still decide (QUARANTINE due, or BLOCKED checked again). */
    public boolean awaitsGates(Instant now) {
        return (state == ReleaseState.QUARANTINE || state == ReleaseState.BLOCKED) && !now.isBefore(effectiveAt);
    }
}
