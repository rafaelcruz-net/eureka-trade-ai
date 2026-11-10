package com.memetrade.identity.infra;

import com.memetrade.identity.domain.purge.IdentityPurge;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * BR2.6 (F4, AC3.1.2): a session leaves {@code session-retention} (30 days) after its end, which is {@code revoked_at}
 * when it was revoked and otherwise the earlier of {@code refresh_expires_at} and {@code absolute_expires_at}. A session
 * whose end is in the future is never selected, whatever its recorded state. Guest and administrator sessions alike
 * (same table).
 *
 * <p>Each batch, inside the transaction the runner opens, picks at most {@code limit} sessions by the expression of
 * the index {@code user_session_end_idx} (NFR3.5), deletes their used refresh digests first (index
 * {@code session_used_refresh_digest_session_idx}) and then the sessions.
 */
@Component
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class SessionPurgeJob implements IdentityPurgeJob {

    /** The end of a session; the same expression as the index of migration 0012. */
    static final String SESSION_END = "coalesce(revoked_at, least(refresh_expires_at, absolute_expires_at))";

    private final JdbcClient jdbc;
    private final IdentityPurgeProperties settings;

    public SessionPurgeJob(JdbcClient jdbc, IdentityPurgeProperties settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    @Override
    public IdentityPurge purge() {
        return IdentityPurge.SESSION_PURGE;
    }

    @Override
    public Instant cutoff(Instant now) {
        return now.minus(settings.sessionRetention());
    }

    @Override
    public BatchCount deleteBatch(Instant cutoff, int limit) {
        List<UUID> ended = jdbc.sql("""
                        select session_id from user_session
                        where %s <= :cutoff
                        order by %s
                        limit :limit
                        for update skip locked
                        """.formatted(SESSION_END, SESSION_END))
                .param("cutoff", Timestamp.from(cutoff))
                .param("limit", limit)
                .query(UUID.class)
                .list();
        if (ended.isEmpty()) {
            return new BatchCount(0, 0);
        }
        int digests = jdbc.sql("delete from session_used_refresh_digest where session_id in (:ids)")
                .param("ids", ended)
                .update();
        int sessions = jdbc.sql("delete from user_session where session_id in (:ids)")
                .param("ids", ended)
                .update();
        return new BatchCount(sessions, digests);
    }

    @Override
    public Optional<String> relatedDetail() {
        return Optional.of("digestsDeleted");
    }
}
