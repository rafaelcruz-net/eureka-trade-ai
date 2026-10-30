package com.memetrade.identity.domain.session;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Test double of the session store with the same conditional updates as PostgreSQL. */
public final class InMemorySessionRepository implements SessionRepository {

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, UUID> usedDigests = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean[]> activity = new HashMap<>();

    @Override
    public void insert(Session session) {
        sessions.put(session.sessionId(), session);
    }

    @Override
    public Optional<Session> findById(UUID sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public Optional<Session> findByRefreshDigestForUpdate(String digest) {
        return sessions.values().stream()
                .filter(s -> s.refreshTokenDigest().equals(digest))
                .findFirst();
    }

    @Override
    public Optional<UUID> findSessionOfUsedDigest(String digest) {
        return Optional.ofNullable(usedDigests.get(digest));
    }

    @Override
    public synchronized boolean rotate(
            UUID sessionId, String oldDigest, String newDigest, Instant newRefreshExpiresAt, Instant now) {
        Session s = sessions.get(sessionId);
        if (s == null
                || s.state() != SessionState.ACTIVE
                || !s.refreshTokenDigest().equals(oldDigest)) {
            return false;
        }
        sessions.put(
                sessionId,
                new Session(
                        s.sessionId(),
                        s.userId(),
                        s.tenantId(),
                        s.role(),
                        newDigest,
                        s.createdAt(),
                        newRefreshExpiresAt,
                        s.absoluteExpiresAt(),
                        s.state(),
                        s.revokedAt()));
        usedDigests.put(oldDigest, sessionId);
        return true;
    }

    @Override
    public synchronized boolean close(UUID sessionId, SessionState target, Instant now) {
        Session s = sessions.get(sessionId);
        if (s == null || s.state() != SessionState.ACTIVE) {
            return false;
        }
        sessions.put(
                sessionId,
                new Session(
                        s.sessionId(),
                        s.userId(),
                        s.tenantId(),
                        s.role(),
                        s.refreshTokenDigest(),
                        s.createdAt(),
                        s.refreshExpiresAt(),
                        s.absoluteExpiresAt(),
                        target,
                        now));
        return true;
    }

    @Override
    public Optional<SessionAuthView> findAuthView(UUID sessionId) {
        return findById(sessionId).map(s -> {
            Boolean[] flags = activity.getOrDefault(sessionId, new Boolean[] {true, true});
            return new SessionAuthView(
                    s.sessionId(),
                    s.userId(),
                    s.tenantId(),
                    s.role(),
                    s.state(),
                    s.absoluteExpiresAt(),
                    flags[0],
                    flags[1]);
        });
    }

    /** Simulates an administrator disabling the user or suspending the tenant. */
    public void setActivity(UUID sessionId, boolean userActive, boolean tenantActive) {
        activity.put(sessionId, new Boolean[] {userActive, tenantActive});
    }

    public int size() {
        return sessions.size();
    }
}
