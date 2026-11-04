package com.memetrade.identity.domain.invite;

import com.memetrade.identity.api.InviteStatus;
import com.memetrade.identity.api.InviteView;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Stored invitation (entities.md Invite). {@code storedStatus} is PENDING, USED or REVOKED; the state shown everywhere
 * is derived at a given instant by {@link #statusAt(Instant)} (BR1.2). {@code issuedBy} is the masked administrator
 * wallet and never leaves the identity module.
 */
public record Invite(
        UUID inviteId,
        UUID tenantId,
        String codeDigest,
        String codeSuffix,
        InviteStatus storedStatus,
        String issuedBy,
        Instant createdAt,
        Instant expiresAt,
        Instant usedAt,
        UUID usedByUserId,
        Instant revokedAt) {

    public Invite {
        Objects.requireNonNull(inviteId, "inviteId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(storedStatus, "storedStatus");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (storedStatus == InviteStatus.EXPIRED) {
            throw new IllegalArgumentException("EXPIRED is derived on read and never stored (BR1.2)");
        }
    }

    /**
     * BR1.2: a PENDING invitation is valid up to and INCLUDING {@code expiresAt} and reads as EXPIRED one instant
     * later; USED and REVOKED are final and never become EXPIRED.
     */
    public InviteStatus statusAt(Instant now) {
        if (storedStatus == InviteStatus.PENDING && now.isAfter(expiresAt)) {
            return InviteStatus.EXPIRED;
        }
        return storedStatus;
    }

    /** The administrator view at {@code now}: never the code (it is not stored) nor {@code issuedBy} (BR1.3). */
    public InviteView viewAt(Instant now) {
        return new InviteView(inviteId, tenantId, statusAt(now), expiresAt, createdAt, usedAt, revokedAt, codeSuffix);
    }
}
