package com.memetrade.identity.infra;

import com.memetrade.identity.api.InviteStatus;
import com.memetrade.identity.domain.invite.Invite;
import com.memetrade.identity.domain.invite.InviteCursor;
import com.memetrade.identity.domain.invite.InviteRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Invitations in PostgreSQL with parameterised SQL; row locks by {@code SELECT … FOR UPDATE} (NFR4.2, NFR4.4). */
@Repository
public class JdbcInviteRepository implements InviteRepository {

    private static final String COLUMNS = """
            invite_id, tenant_id, code_digest, code_suffix, status, issued_by, created_at, expires_at, used_at,
            used_by_user_id, revoked_at
            """;

    private final JdbcClient jdbc;

    public JdbcInviteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Invite invite) {
        jdbc.sql("""
                        insert into invite (invite_id, tenant_id, code_digest, code_suffix, status, issued_by,
                                            created_at, expires_at)
                        values (:id, :tenant, :digest, :suffix, :status, :issuedBy, :created, :expires)
                        """)
                .param("id", invite.inviteId())
                .param("tenant", invite.tenantId())
                .param("digest", invite.codeDigest())
                .param("suffix", invite.codeSuffix())
                .param("status", invite.storedStatus().name())
                .param("issuedBy", invite.issuedBy())
                .param("created", Timestamp.from(invite.createdAt()))
                .param("expires", Timestamp.from(invite.expiresAt()))
                .update();
    }

    @Override
    public Optional<Invite> findByDigest(String codeDigest) {
        return jdbc.sql("select " + COLUMNS + " from invite where code_digest = :digest")
                .param("digest", codeDigest)
                .query(JdbcInviteRepository::map)
                .optional();
    }

    @Override
    public Optional<Invite> findByDigestForUpdate(String codeDigest) {
        return jdbc.sql("select " + COLUMNS + " from invite where code_digest = :digest for update")
                .param("digest", codeDigest)
                .query(JdbcInviteRepository::map)
                .optional();
    }

    @Override
    public Optional<Invite> findByIdForUpdate(UUID inviteId) {
        return jdbc.sql("select " + COLUMNS + " from invite where invite_id = :id for update")
                .param("id", inviteId)
                .query(JdbcInviteRepository::map)
                .optional();
    }

    @Override
    public boolean markUsed(UUID inviteId, UUID userId, Instant usedAt) {
        return jdbc.sql("""
                        update invite set status = 'USED', used_at = :at, used_by_user_id = :user
                        where invite_id = :id and status = 'PENDING'
                        """)
                        .param("at", Timestamp.from(usedAt))
                        .param("user", userId)
                        .param("id", inviteId)
                        .update()
                == 1;
    }

    @Override
    public boolean markRevoked(UUID inviteId, Instant revokedAt) {
        return jdbc.sql("""
                        update invite set status = 'REVOKED', revoked_at = :at
                        where invite_id = :id and status = 'PENDING'
                        """)
                        .param("at", Timestamp.from(revokedAt))
                        .param("id", inviteId)
                        .update()
                == 1;
    }

    @Override
    public List<Invite> page(Optional<InviteCursor> after, int limit) {
        if (after.isEmpty()) {
            return jdbc.sql("select " + COLUMNS + " from invite order by created_at desc, invite_id limit :limit")
                    .param("limit", limit)
                    .query(JdbcInviteRepository::map)
                    .list();
        }
        return jdbc.sql("select " + COLUMNS + """
                         from invite
                        where created_at < :created or (created_at = :created and invite_id > :id)
                        order by created_at desc, invite_id
                        limit :limit
                        """)
                .param("created", Timestamp.from(after.get().createdAt()))
                .param("id", after.get().inviteId())
                .param("limit", limit)
                .query(JdbcInviteRepository::map)
                .list();
    }

    @Override
    public Map<InviteStatus, Long> countByStatusAt(Instant now) {
        Map<InviteStatus, Long> counts = new EnumMap<>(InviteStatus.class);
        jdbc.sql("""
                        select case when status = 'PENDING' and expires_at < :now then 'EXPIRED' else status end as state,
                               count(*) as total
                        from invite
                        group by 1
                        """)
                .param("now", Timestamp.from(now))
                .query((rs, n) -> Map.entry(InviteStatus.valueOf(rs.getString("state")), rs.getLong("total")))
                .list()
                .forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
        return counts;
    }

    private static Invite map(ResultSet rs, int row) throws SQLException {
        return new Invite(
                rs.getObject("invite_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("code_digest"),
                rs.getString("code_suffix"),
                InviteStatus.valueOf(rs.getString("status")),
                rs.getString("issued_by"),
                instant(rs, "created_at"),
                instant(rs, "expires_at"),
                instant(rs, "used_at"),
                rs.getObject("used_by_user_id", UUID.class),
                instant(rs, "revoked_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
