package com.memetrade.identity.infra;

import com.memetrade.identity.domain.account.AccountDetailsRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** One query by the keys of the person, its tenant and its session (NFR3.2, NFR5.1). */
@Repository
public class JdbcAccountDetailsRepository implements AccountDetailsRepository {

    private final JdbcClient jdbc;

    public JdbcAccountDetailsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AccountDetails> find(UUID userId, UUID tenantId, UUID sessionId) {
        return jdbc.sql("""
                        select w.address, w.provider, s.absolute_expires_at, a.terms_version, a.accepted_at
                        from app_user u
                        join wallet w on w.user_id = u.user_id
                        join user_session s on s.user_id = u.user_id and s.tenant_id = u.tenant_id
                        left join lateral (
                            select t.terms_version, t.accepted_at from terms_acceptance t
                            where t.user_id = u.user_id and t.tenant_id = u.tenant_id
                            order by t.accepted_at desc limit 1
                        ) a on true
                        where u.user_id = :user and u.tenant_id = :tenant and s.session_id = :session
                        """)
                .param("user", userId)
                .param("tenant", tenantId)
                .param("session", sessionId)
                .query((rs, n) -> new AccountDetails(
                        rs.getString("address"),
                        rs.getString("provider"),
                        rs.getTimestamp("absolute_expires_at").toInstant(),
                        rs.getString("terms_version"),
                        instant(rs.getTimestamp("accepted_at"))))
                .optional();
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
