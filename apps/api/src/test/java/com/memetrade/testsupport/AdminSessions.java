package com.memetrade.testsupport;

import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.identity.domain.session.AccessTokens;
import com.memetrade.identity.domain.session.AuthenticatedSession;
import com.memetrade.identity.domain.session.RefreshTokens;
import com.memetrade.identity.domain.session.SessionRepository;
import com.memetrade.identity.domain.session.SessionService;
import com.memetrade.identity.domain.session.SessionSettings;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TEST-ONLY administrator sessions (NFR6.1, functional-spec.md "Dependência da U8"). The administrator entry arrives
 * in U8 and {@code user_session} accepts only BETA_USER, so no production path can create an ADMIN principal before
 * U8; ArchUnit rules keep it that way. Integration tests use this {@link SessionService} instead: a token issued by
 * {@link #openAdminSession()} authenticates as {@link Role#ADMIN}; every other token goes through the real check.
 */
public class AdminSessions extends SessionService {

    private static final String TOKEN_PREFIX = "test-admin-session-";

    private final Map<String, AuthenticatedSession> adminTokens = new ConcurrentHashMap<>();

    public AdminSessions(
            SessionRepository sessions,
            AccessTokens accessTokens,
            RefreshTokens refreshTokens,
            SessionSettings settings,
            AuditEventPublisher audit,
            Clock clock) {
        super(sessions, accessTokens, refreshTokens, settings, audit, clock);
    }

    /** Opens an administrator session and returns its bearer token (valid until the context closes). */
    public AdminSession openAdminSession() {
        UUID adminId = UUID.randomUUID();
        String token = TOKEN_PREFIX + UUID.randomUUID();
        adminTokens.put(
                token,
                new AuthenticatedSession(new Principal(UUID.randomUUID(), adminId, Role.ADMIN), UUID.randomUUID()));
        return new AdminSession(adminId, token);
    }

    /**
     * U8: a session for an administrator that EXISTS in {@code app_user} (role ADMIN), so the rows that reference the
     * administrator (kill switch history, state) satisfy their foreign keys.
     */
    public AdminSession openAdminSessionFor(UUID tenantId, UUID adminId) {
        String token = TOKEN_PREFIX + UUID.randomUUID();
        adminTokens.put(
                token, new AuthenticatedSession(new Principal(tenantId, adminId, Role.ADMIN), UUID.randomUUID()));
        return new AdminSession(adminId, token);
    }

    @Override
    public Optional<AuthenticatedSession> authenticate(String accessToken) {
        AuthenticatedSession admin = accessToken == null ? null : adminTokens.get(accessToken);
        return admin != null ? Optional.of(admin) : super.authenticate(accessToken);
    }

    /** Administrator id (the actor of AUDIT_QUERIED) and its bearer token. */
    public record AdminSession(UUID adminId, String token) {
        public String bearer() {
            return "Bearer " + token;
        }
    }
}
