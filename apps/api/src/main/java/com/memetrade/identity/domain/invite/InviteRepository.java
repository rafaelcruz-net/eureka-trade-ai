package com.memetrade.identity.domain.invite;

import com.memetrade.identity.api.InviteStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persistence port of invitations (identity module table {@code invite}). Lookups are by digest, never by code. */
public interface InviteRepository {

    void insert(Invite invite);

    /** Read without a lock (the guest lookup, F4). */
    Optional<Invite> findByDigest(String codeDigest);

    /** {@code SELECT … FOR UPDATE}: the verification with an invitation locks the row before reading its state. */
    Optional<Invite> findByDigestForUpdate(String codeDigest);

    /** {@code SELECT … FOR UPDATE}: the revocation locks the same row (F3.4, NFR4.4). */
    Optional<Invite> findByIdForUpdate(UUID inviteId);

    /** PENDING → USED; false when the row was no longer PENDING. */
    boolean markUsed(UUID inviteId, UUID userId, Instant usedAt);

    /** PENDING → REVOKED; false when the row was no longer PENDING. */
    boolean markRevoked(UUID inviteId, Instant revokedAt);

    /**
     * Up to {@code limit} invitations ordered by {@code createdAt} descending and {@code inviteId}, strictly after
     * {@code after} when present (keyset pagination, NFR2.2).
     */
    List<Invite> page(Optional<InviteCursor> after, int limit);

    /** Invitations per DERIVED state at {@code now} (BR1.2); a state without rows is absent from the map. */
    Map<InviteStatus, Long> countByStatusAt(Instant now);
}
