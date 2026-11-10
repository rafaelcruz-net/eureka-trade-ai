package com.memetrade.identity.infra;

import com.memetrade.identity.domain.terms.TermsAcceptanceStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * u3-reacceptance (BR3.4, BR3.5, NFR3.1, NFR3.5): insert-only writes of the acceptances without an invitation. A
 * concurrent acceptance of the same version waits on the unique index and inserts nothing; the stored row is then read
 * by the person, the tenant and the version. The application login has no UPDATE, DELETE nor TRUNCATE on the table.
 */
@Repository
public class JdbcTermsAcceptanceStore implements TermsAcceptanceStore {

    private final JdbcClient jdbc;

    public JdbcTermsAcceptanceStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfAbsent(NewAcceptance acceptance) {
        int inserted = jdbc.sql("""
                        insert into terms_acceptance
                            (acceptance_id, user_id, tenant_id, terms_version, accepted_at, invite_id, source)
                        values (:id, :user, :tenant, :version, :acceptedAt, null, :source)
                        on conflict on constraint uq_terms_acceptance_user_version do nothing
                        """)
                .param("id", acceptance.acceptanceId())
                .param("user", acceptance.userId())
                .param("tenant", acceptance.tenantId())
                .param("version", acceptance.version())
                .param("acceptedAt", Timestamp.from(acceptance.acceptedAt()))
                .param("source", acceptance.source().name())
                .update();
        return inserted == 1;
    }

    @Override
    public Optional<Instant> acceptedAt(UUID userId, UUID tenantId, String version) {
        return jdbc.sql("""
                        select accepted_at from terms_acceptance
                        where user_id = :user and terms_version = :version and tenant_id = :tenant
                        """)
                .param("user", userId)
                .param("version", version)
                .param("tenant", tenantId)
                .query((rs, n) -> rs.getTimestamp("accepted_at").toInstant())
                .optional();
    }
}
