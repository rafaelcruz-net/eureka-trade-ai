package com.memetrade.identity.domain.session;

import com.memetrade.identity.api.Role;
import java.time.Instant;
import java.util.UUID;

/** Session opened by a login; authenticates product use and never authorises trading (BR1.7). */
public record Session(
        UUID sessionId,
        UUID userId,
        UUID tenantId,
        Role role,
        String refreshTokenDigest,
        Instant createdAt,
        Instant refreshExpiresAt,
        Instant absoluteExpiresAt,
        SessionState state,
        Instant revokedAt) {

    public boolean deadlinePassed(Instant now) {
        return now.isAfter(refreshExpiresAt) || now.isAfter(absoluteExpiresAt);
    }
}
