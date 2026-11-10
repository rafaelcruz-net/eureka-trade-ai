package com.memetrade.identity.domain.session;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence port of sessions. Only digests of refresh tokens are stored (NFR6.6). */
public interface SessionRepository {

    void insert(Session session);

    Optional<Session> findById(UUID sessionId);

    /** The session whose CURRENT refresh token has this digest, locked for update. */
    Optional<Session> findByRefreshDigestForUpdate(String digest);

    /** The session that already exchanged a refresh token with this digest (reuse detection, BR1.15). */
    Optional<UUID> findSessionOfUsedDigest(String digest);

    /** Rotates the refresh token of an ACTIVE session; the old digest is remembered as used. */
    boolean rotate(UUID sessionId, String oldDigest, String newDigest, Instant newRefreshExpiresAt, Instant now);

    /** ACTIVE → {@code target} (REVOKED or EXPIRED); false when the session was no longer ACTIVE. */
    boolean close(UUID sessionId, SessionState target, Instant now);

    Optional<SessionAuthView> findAuthView(UUID sessionId);

    /**
     * u5-anonymization (BR5.6, NFR5.6): the same view, with a SHARED lock on the account row until the end of the
     * refresh transaction. Called after the session lock, in the order sessions then account of the anonymization.
     */
    Optional<SessionAuthView> findAuthViewSharingAccount(UUID sessionId);
}
