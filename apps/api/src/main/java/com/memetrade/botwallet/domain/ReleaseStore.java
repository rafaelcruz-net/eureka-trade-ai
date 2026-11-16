package com.memetrade.botwallet.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 261113-trading-real-bot U5: persistence port of the releases (BR1.2 to BR1.6). Every change of state is a
 * compare-and-set on the expected state (U5-NFR4.1).
 */
public interface ReleaseStore {

    /** The ACTIVE bot wallet of a person, with its tenant; the release of the administrator knows only the person. */
    Optional<WalletOfPerson> activeWalletOf(UUID userId);

    /** The tenant of the person, from any bot wallet (ACTIVE or CLOSED), for the audit of a revocation. */
    Optional<UUID> tenantOf(UUID userId);

    /** BR1.3 (U5-NFR4.1): the one lock of the releases on the MAINNET, until the end of the transaction. */
    void lockMainnetReleases();

    boolean hasOpenRelease(UUID userId, Network network);

    boolean hasActiveRelease(UUID userId, Network network);

    /** The releases not revoked on {@code network} of every person but {@code founder}. */
    long openGuestReleases(Network network, UUID founder);

    /** Inserts a release; {@code false} when the unique index found another open release of the person and network. */
    boolean insert(RealRelease release);

    /** BR1.6: revokes every open release of the person; returns the ids revoked (none when already revoked). */
    List<UUID> revokeOpen(UUID userId, Instant at, UUID by);

    /** BR1.4: the releases whose quarantine passed, in QUARANTINE, and every BLOCKED one, oldest first. */
    List<RealRelease> awaitingGates(Instant now);

    /** BR1.4: compare-and-set of the state after the gates; {@code false} when the release changed meanwhile. */
    boolean moveGates(UUID releaseId, ReleaseState expected, ReleaseState next, BlockedReason reason, Instant at);

    /** BR1.4 (b): the person has a divergence OPEN or COMPENSATED on one of its bot wallets. */
    boolean hasOpenDivergence(UUID userId);

    /** The ACTIVE bot wallet of a person. */
    record WalletOfPerson(UUID tenantId, UUID botWalletId) {}
}
