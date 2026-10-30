package com.memetrade.identity.infra;

import com.memetrade.identity.api.Role;
import com.memetrade.identity.domain.session.Session;
import com.memetrade.identity.domain.session.SessionAuthView;
import com.memetrade.identity.domain.session.SessionRepository;
import com.memetrade.identity.domain.session.SessionState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSessionRepository implements SessionRepository {

    private final JdbcClient jdbc;

    public JdbcSessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Session session) {
        jdbc.sql("""
                        insert into user_session
                          (session_id, user_id, tenant_id, role, refresh_token_digest, created_at,
                           refresh_expires_at, absolute_expires_at, state, revoked_at)
                        values (:id, :user, :tenant, :role, :digest, :created, :refresh, :absolute, :state, null)
                        """)
                .param("id", session.sessionId())
                .param("user", session.userId())
                .param("tenant", session.tenantId())
                .param("role", session.role().name())
                .param("digest", session.refreshTokenDigest())
                .param("created", Timestamp.from(session.createdAt()))
                .param("refresh", Timestamp.from(session.refreshExpiresAt()))
                .param("absolute", Timestamp.from(session.absoluteExpiresAt()))
                .param("state", session.state().name())
                .update();
    }

    @Override
    public Optional<Session> findById(UUID sessionId) {
        return jdbc.sql("select * from user_session where session_id = :id")
                .param("id", sessionId)
                .query(JdbcSessionRepository::map)
                .optional();
    }

    @Override
    public Optional<Session> findByRefreshDigestForUpdate(String digest) {
        return jdbc.sql("select * from user_session where refresh_token_digest = :digest for update")
                .param("digest", digest)
                .query(JdbcSessionRepository::map)
                .optional();
    }

    @Override
    public Optional<UUID> findSessionOfUsedDigest(String digest) {
        return jdbc.sql("select session_id from session_used_refresh_digest where digest = :digest")
                .param("digest", digest)
                .query(UUID.class)
                .optional();
    }

    @Override
    public boolean rotate(
            UUID sessionId, String oldDigest, String newDigest, Instant newRefreshExpiresAt, Instant now) {
        int rows = jdbc.sql("""
                        update user_session set refresh_token_digest = :newDigest, refresh_expires_at = :refresh
                        where session_id = :id and refresh_token_digest = :oldDigest and state = 'ACTIVE'
                        """)
                .param("newDigest", newDigest)
                .param("refresh", Timestamp.from(newRefreshExpiresAt))
                .param("id", sessionId)
                .param("oldDigest", oldDigest)
                .update();
        if (rows != 1) {
            return false;
        }
        jdbc.sql("insert into session_used_refresh_digest (digest, session_id, used_at) values (:d, :id, :now)")
                .param("d", oldDigest)
                .param("id", sessionId)
                .param("now", Timestamp.from(now))
                .update();
        return true;
    }

    @Override
    public boolean close(UUID sessionId, SessionState target, Instant now) {
        return jdbc.sql("""
                                update user_session set state = :state, revoked_at = :now
                                where session_id = :id and state = 'ACTIVE'
                                """)
                        .param("state", target.name())
                        .param("now", Timestamp.from(now))
                        .param("id", sessionId)
                        .update()
                == 1;
    }

    @Override
    public Optional<SessionAuthView> findAuthView(UUID sessionId) {
        return jdbc.sql("""
                        select s.session_id, s.user_id, s.tenant_id, s.role, s.state, s.absolute_expires_at,
                               u.status as user_status, t.status as tenant_status
                        from user_session s
                        join app_user u on u.user_id = s.user_id
                        join tenant t on t.tenant_id = s.tenant_id
                        where s.session_id = :id
                        """)
                .param("id", sessionId)
                .query((rs, n) -> new SessionAuthView(
                        rs.getObject("session_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        Role.valueOf(rs.getString("role")),
                        SessionState.valueOf(rs.getString("state")),
                        rs.getTimestamp("absolute_expires_at").toInstant(),
                        "ACTIVE".equals(rs.getString("user_status")),
                        "ACTIVE".equals(rs.getString("tenant_status"))))
                .optional();
    }

    private static Session map(ResultSet rs, int row) throws SQLException {
        Timestamp revoked = rs.getTimestamp("revoked_at");
        return new Session(
                rs.getObject("session_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                Role.valueOf(rs.getString("role")),
                rs.getString("refresh_token_digest"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("refresh_expires_at").toInstant(),
                rs.getTimestamp("absolute_expires_at").toInstant(),
                SessionState.valueOf(rs.getString("state")),
                revoked == null ? null : revoked.toInstant());
    }
}
