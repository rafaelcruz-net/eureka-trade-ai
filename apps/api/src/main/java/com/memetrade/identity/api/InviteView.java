package com.memetrade.identity.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An invitation as the administration sees it (C17 {@code Invite}, BR1.3): derived state, validity and the last 4
 * characters of the code, NEVER the code nor who issued it. {@code usedAt} and {@code revokedAt} are {@code null}
 * when they do not apply. Instants are UTC.
 */
public record InviteView(
        UUID inviteId,
        UUID tenantId,
        InviteStatus status,
        Instant expiresAt,
        Instant createdAt,
        Instant usedAt,
        Instant revokedAt,
        String codeSuffix) {

    public InviteView {
        Objects.requireNonNull(inviteId, "inviteId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(codeSuffix, "codeSuffix");
    }
}
