package com.memetrade.identity.domain.session;

import com.memetrade.identity.api.Role;
import java.time.Instant;
import java.util.UUID;

/**
 * Tokens handed to the app after login or refresh. The refresh token goes only to the HttpOnly cookie and is never
 * logged, audited or stored (only its digest is).
 */
public record OpenedSession(
        UUID sessionId,
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshExpiresAt,
        Instant sessionExpiresAt,
        Role role) {

    @Override
    public String toString() {
        return "OpenedSession[sessionId=" + sessionId + ", role=" + role + "]";
    }
}
