package com.memetrade.identity.infra;

import com.memetrade.identity.domain.idempotency.IdempotencyRecord;
import com.memetrade.identity.domain.idempotency.IdempotencyStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** PostgreSQL implementation of the idempotency store; the (scope, key) primary key serialises concurrent claims. */
@Repository
public class JdbcIdempotencyStore implements IdempotencyStore {

    private final JdbcClient jdbc;

    public JdbcIdempotencyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<IdempotencyRecord> findActive(String scope, UUID key, Instant now) {
        return jdbc.sql("""
                        select scope, idempotency_key, request_digest, state, response_status, response_body::text,
                               created_at, expires_at
                        from idempotency_record
                        where scope = :scope and idempotency_key = :key and expires_at > :now
                        """)
                .param("scope", scope)
                .param("key", key)
                .param("now", Timestamp.from(now))
                .query(JdbcIdempotencyStore::map)
                .optional();
    }

    @Override
    public boolean tryClaim(String scope, UUID key, String requestDigest, Instant now, Instant provisionalExpiresAt) {
        // A concurrent insert of the same key blocks on the primary key until the other transaction ends; an
        // expired record is replaced so the same key can start a new execution (BR1.10).
        int rows = jdbc.sql("""
                        insert into idempotency_record
                          (scope, idempotency_key, request_digest, state, response_status, response_body,
                           created_at, expires_at)
                        values (:scope, :key, :digest, 'IN_PROGRESS', null, null, :now, :expires)
                        on conflict (scope, idempotency_key) do update
                          set request_digest = excluded.request_digest,
                              state = 'IN_PROGRESS',
                              response_status = null,
                              response_body = null,
                              created_at = excluded.created_at,
                              expires_at = excluded.expires_at
                          where idempotency_record.expires_at <= :now
                        """)
                .param("scope", scope)
                .param("key", key)
                .param("digest", requestDigest)
                .param("now", Timestamp.from(now))
                .param("expires", Timestamp.from(provisionalExpiresAt))
                .update();
        return rows == 1;
    }

    @Override
    public void complete(String scope, UUID key, int status, String body, Instant expiresAt) {
        int rows = jdbc.sql("""
                        update idempotency_record
                        set state = 'COMPLETED', response_status = :status,
                            response_body = cast(:body as jsonb), expires_at = :expires
                        where scope = :scope and idempotency_key = :key and state = 'IN_PROGRESS'
                        """)
                .param("status", status)
                .param("body", body == null || body.isEmpty() ? null : body)
                .param("expires", Timestamp.from(expiresAt))
                .param("scope", scope)
                .param("key", key)
                .update();
        if (rows != 1) {
            throw new IllegalStateException("Idempotency claim missing when completing the request");
        }
    }

    @Override
    public void release(String scope, UUID key) {
        jdbc.sql(
                        "delete from idempotency_record where scope = :scope and idempotency_key = :key and state = 'IN_PROGRESS'")
                .param("scope", scope)
                .param("key", key)
                .update();
    }

    @Override
    public int deleteExpired(Instant now, int batchSize) {
        return jdbc.sql("""
                        delete from idempotency_record
                        where ctid in (
                          select ctid from idempotency_record where expires_at <= :now limit :batch)
                        """)
                .param("now", Timestamp.from(now))
                .param("batch", batchSize)
                .update();
    }

    private static IdempotencyRecord map(ResultSet rs, int row) throws SQLException {
        return new IdempotencyRecord(
                rs.getString("scope"),
                rs.getObject("idempotency_key", UUID.class),
                rs.getString("request_digest"),
                IdempotencyRecord.State.valueOf(rs.getString("state")),
                rs.getObject("response_status", Integer.class),
                rs.getString("response_body"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant());
    }
}
