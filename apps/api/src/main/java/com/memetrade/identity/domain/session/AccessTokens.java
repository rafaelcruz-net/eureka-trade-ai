package com.memetrade.identity.domain.session;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Port of the signed 15-minute access token that carries the session id (BR1.14). */
public interface AccessTokens {

    String issue(Claims claims);

    /** Verified, unexpired claims, or empty for anything else (including a login signature used as a token). */
    Optional<Claims> verify(String token, Instant now);

    record Claims(UUID sessionId, UUID userId, Instant issuedAt, Instant expiresAt) {}
}
