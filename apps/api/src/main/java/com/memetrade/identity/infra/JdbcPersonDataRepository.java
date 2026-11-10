package com.memetrade.identity.infra;

import com.memetrade.identity.api.PersonDataPort.AcceptedVersion;
import com.memetrade.identity.domain.person.PersonDataRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * J1 reads of u4-data-requests: one query per question, each by the key of the person (and its tenant when the caller
 * is the person's own request), through the primary keys and the indexes of Identity ({@code app_user_pkey},
 * {@code wallet.user_id}, {@code user_session_user_idx}, {@code uq_terms_acceptance_user_version}).
 */
@Repository
public class JdbcPersonDataRepository implements PersonDataRepository {

    private final JdbcClient jdbc;

    public JdbcPersonDataRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PersonRow> findPerson(UUID tenantId, UUID userId) {
        return jdbc.sql("""
                        select w.address, w.provider, u.created_at,
                               (select max(s.created_at) from user_session s
                                where s.user_id = u.user_id and s.tenant_id = u.tenant_id) as last_login_at
                        from app_user u
                        left join wallet w on w.user_id = u.user_id
                        where u.user_id = :user and u.tenant_id = :tenant
                        """)
                .param("user", userId)
                .param("tenant", tenantId)
                .query((rs, n) -> new PersonRow(
                        Optional.ofNullable(rs.getString("address")),
                        Optional.ofNullable(rs.getString("provider")),
                        rs.getTimestamp("created_at").toInstant(),
                        instant(rs.getTimestamp("last_login_at"))))
                .optional();
    }

    @Override
    public List<AcceptedVersion> acceptedVersions(UUID tenantId, UUID userId) {
        return jdbc.sql("""
                        select terms_version, accepted_at from terms_acceptance
                        where user_id = :user and tenant_id = :tenant
                        order by accepted_at, terms_version
                        """)
                .param("user", userId)
                .param("tenant", tenantId)
                .query((rs, n) -> new AcceptedVersion(
                        rs.getString("terms_version"),
                        rs.getTimestamp("accepted_at").toInstant()))
                .list();
    }

    @Override
    public Optional<AccountRow> findAccount(UUID userId) {
        return jdbc.sql("""
                        select u.user_id, u.tenant_id, u.status, w.address
                        from app_user u
                        left join wallet w on w.user_id = u.user_id
                        where u.user_id = :user
                        """)
                .param("user", userId)
                .query((rs, n) -> new AccountRow(
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("status"),
                        Optional.ofNullable(rs.getString("address"))))
                .optional();
    }

    private static Optional<Instant> instant(Timestamp value) {
        return Optional.ofNullable(value).map(Timestamp::toInstant);
    }
}
