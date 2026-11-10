package com.memetrade.identity.domain.session;

import com.memetrade.audit.api.AuditActions;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Session lifecycle (BR1.9, BR1.12, BR1.14, BR1.15): open, per-request check, refresh with rotation and reuse
 * detection, and logout. The session authenticates product use only; it never authorises trading (BR1.7).
 */
public class SessionService {

    private final SessionRepository sessions;
    private final AccessTokens accessTokens;
    private final RefreshTokens refreshTokens;
    private final SessionSettings settings;
    private final AuditEventPublisher audit;
    private final Clock clock;

    public SessionService(
            SessionRepository sessions,
            AccessTokens accessTokens,
            RefreshTokens refreshTokens,
            SessionSettings settings,
            AuditEventPublisher audit,
            Clock clock) {
        this.sessions = sessions;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
    }

    /** Creates an ACTIVE session for the account; runs inside the verification transaction. */
    public OpenedSession open(Account account, Instant now) {
        return openSession(account, Role.BETA_USER, now, guestPolicy());
    }

    /**
     * U8 (Q1 of the functional design): a session with the role of the account (checked by the administrator entry
     * before calling), access tokens of {@code policy.accessTtl()} and an absolute life of {@code sessionMaxAge} that the
     * refresh never extends. Runs inside the verification transaction.
     */
    public OpenedSession openWithPolicy(Account account, Instant now, Duration sessionMaxAge, RefreshPolicy policy) {
        Instant absoluteExpiresAt = now.plus(sessionMaxAge);
        return openSession(account, account.role(), now, policy, absoluteExpiresAt);
    }

    private OpenedSession openSession(Account account, Role role, Instant now, RefreshPolicy policy) {
        return openSession(account, role, now, policy, now.plus(settings.sessionMaxAge()));
    }

    private OpenedSession openSession(
            Account account, Role role, Instant now, RefreshPolicy policy, Instant absoluteExpiresAt) {
        UUID sessionId = UUID.randomUUID();
        String refreshToken = refreshTokens.newToken();
        Instant refreshExpiresAt = refreshDeadline(policy, now, absoluteExpiresAt);
        sessions.insert(new Session(
                sessionId,
                account.userId(),
                account.tenantId(),
                role,
                RefreshTokens.digest(refreshToken),
                now,
                refreshExpiresAt,
                absoluteExpiresAt,
                SessionState.ACTIVE,
                null));
        return tokensFor(
                sessionId, account.userId(), refreshToken, refreshExpiresAt, absoluteExpiresAt, now, role, policy);
    }

    /** The guest rules of U1: BETA_USER, 15-minute access, refresh sliding by 8 hours inside the 12 hours. */
    public RefreshPolicy guestPolicy() {
        return new RefreshPolicy(
                Role.BETA_USER, settings.accessTokenTtl(), Optional.of(settings.refreshTtl()), s -> true);
    }

    /** Revokes an ACTIVE session (U8 logout and loss of the administrator role); true when it was still ACTIVE. */
    @Transactional
    public boolean revoke(UUID sessionId) {
        return sessions.close(sessionId, SessionState.REVOKED, clock.instant());
    }

    /**
     * Which sessions a refresh route renews (U8): the role of the route, the lifetime of the new access token, how far
     * the refresh slides ({@code empty} = never past the absolute end, which is fixed at opening) and whether the
     * session may still be renewed ({@code stillAllowed} false revokes it, BR1.4 of U8).
     */
    public record RefreshPolicy(
            Role role,
            Duration accessTtl,
            Optional<Duration> slidingRefresh,
            java.util.function.Predicate<Session> stillAllowed) {}

    /**
     * BR1.14: a valid, unexpired access token is accepted only while its session is ACTIVE and its user and tenant
     * are ACTIVE, even within the token's 15 minutes. One primary-key read per request.
     */
    @Transactional(readOnly = true)
    public Optional<AuthenticatedSession> authenticate(String accessToken) {
        Instant now = clock.instant();
        return accessTokens.verify(accessToken, now).flatMap(claims -> sessions.findAuthView(claims.sessionId())
                .filter(view -> view.userId().equals(claims.userId()))
                .filter(view -> view.state() == SessionState.ACTIVE)
                .filter(view -> view.userActive() && view.tenantActive())
                .filter(view -> !now.isAfter(view.absoluteExpiresAt()))
                .map(view -> new AuthenticatedSession(
                        new Principal(view.tenantId(), view.userId(), view.role()), view.sessionId())));
    }

    /** BR1.9 + BR1.15: rotates the refresh token; a token already exchanged revokes the whole session. */
    @Transactional
    public RefreshOutcome refresh(String refreshToken, String correlationId) {
        return refresh(refreshToken, correlationId, guestPolicy());
    }

    /**
     * The refresh of one route (guest or administrator): a token of a session of another role is unknown here (the
     * cookies live on different paths; a token sent to the wrong route renews nothing).
     */
    @Transactional
    public RefreshOutcome refresh(String refreshToken, String correlationId, RefreshPolicy policy) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return new RefreshOutcome.Refused(RefreshOutcome.Reason.MISSING);
        }
        Instant now = clock.instant();
        String digest = RefreshTokens.digest(refreshToken);
        Optional<Session> current = sessions.findByRefreshDigestForUpdate(digest);
        if (current.isEmpty()) {
            return sessions.findSessionOfUsedDigest(digest)
                    .map(sessionId -> revokeForReuse(sessionId, correlationId, now))
                    .orElse(new RefreshOutcome.Refused(RefreshOutcome.Reason.UNKNOWN));
        }
        if (current.get().role() != policy.role()) {
            return new RefreshOutcome.Refused(RefreshOutcome.Reason.UNKNOWN);
        }
        return renew(current.get(), digest, now, policy);
    }

    /** BR1.12: revokes an ACTIVE session and records LOGOUT; an already closed session is a silent no-op. */
    @Transactional
    public void logout(AuthenticatedSession authenticated, String correlationId) {
        Instant now = clock.instant();
        if (sessions.close(authenticated.sessionId(), SessionState.REVOKED, now)) {
            Principal principal = authenticated.principal();
            audit.publish(AuditEventCommand.person(
                    principal.tenantId(),
                    principal.userId(),
                    AuditActions.LOGOUT,
                    "session:" + authenticated.sessionId(),
                    correlationId,
                    now,
                    Map.of()));
        }
    }

    private RefreshOutcome renew(Session session, String digest, Instant now, RefreshPolicy policy) {
        if (session.state() != SessionState.ACTIVE) {
            return new RefreshOutcome.Refused(RefreshOutcome.Reason.CLOSED);
        }
        // u5-anonymization (BR5.6, NFR5.6): after the session lock, the account under a shared lock (the order sessions
        // then account of the anonymization); an account no longer ACTIVE (ANONYMIZED included) revokes the session.
        Optional<SessionAuthView> view = sessions.findAuthViewSharingAccount(session.sessionId());
        if (view.isEmpty() || !view.get().userActive() || !view.get().tenantActive()) {
            sessions.close(session.sessionId(), SessionState.REVOKED, now);
            return new RefreshOutcome.Refused(RefreshOutcome.Reason.INACTIVE_ACCOUNT);
        }
        if (session.deadlinePassed(now)) {
            sessions.close(session.sessionId(), SessionState.EXPIRED, now);
            return new RefreshOutcome.Refused(RefreshOutcome.Reason.EXPIRED);
        }
        if (!policy.stillAllowed().test(session)) {
            sessions.close(session.sessionId(), SessionState.REVOKED, now);
            return new RefreshOutcome.Refused(RefreshOutcome.Reason.NOT_ALLOWED);
        }
        String newToken = refreshTokens.newToken();
        Instant newRefreshExpiresAt = refreshDeadline(policy, now, session.absoluteExpiresAt());
        if (!sessions.rotate(session.sessionId(), digest, RefreshTokens.digest(newToken), newRefreshExpiresAt, now)) {
            return new RefreshOutcome.Refused(RefreshOutcome.Reason.CONCURRENT_ROTATION);
        }
        return new RefreshOutcome.Renewed(tokensFor(
                session.sessionId(),
                session.userId(),
                newToken,
                newRefreshExpiresAt,
                session.absoluteExpiresAt(),
                now,
                session.role(),
                policy));
    }

    /** Sliding refresh bounded by the absolute end; without sliding, the absolute end itself. */
    private static Instant refreshDeadline(RefreshPolicy policy, Instant now, Instant absoluteExpiresAt) {
        return policy.slidingRefresh()
                .map(window -> earliest(now.plus(window), absoluteExpiresAt))
                .orElse(absoluteExpiresAt);
    }

    private RefreshOutcome revokeForReuse(UUID sessionId, String correlationId, Instant now) {
        if (sessions.close(sessionId, SessionState.REVOKED, now)) {
            sessions.findById(sessionId)
                    .ifPresent(session -> audit.publish(AuditEventCommand.person(
                            session.tenantId(),
                            session.userId(),
                            AuditActions.SESSION_REFRESH_REUSED,
                            "session:" + sessionId,
                            correlationId,
                            now,
                            Map.of())));
        }
        return new RefreshOutcome.Refused(RefreshOutcome.Reason.REUSED);
    }

    private OpenedSession tokensFor(
            UUID sessionId,
            UUID userId,
            String refreshToken,
            Instant refreshExpiresAt,
            Instant absoluteExpiresAt,
            Instant now,
            Role role,
            RefreshPolicy policy) {
        Instant accessExpiresAt = earliest(now.plus(policy.accessTtl()), absoluteExpiresAt);
        String accessToken = accessTokens.issue(new AccessTokens.Claims(sessionId, userId, now, accessExpiresAt));
        return new OpenedSession(
                sessionId, accessToken, accessExpiresAt, refreshToken, refreshExpiresAt, absoluteExpiresAt, role);
    }

    private static Instant earliest(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
