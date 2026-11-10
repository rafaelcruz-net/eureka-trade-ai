package com.memetrade.privacy.infra;

import com.memetrade.privacy.domain.request.IdentityVerification;
import com.memetrade.privacy.domain.request.VerificationState;
import com.memetrade.privacy.domain.request.VerificationStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The verifications over {@code data_subject_verification} (migration 0014). The confirmation and the manual expiration
 * are conditional on PENDING (and on the validity for the confirmation), so a second confirmation and the expiration job
 * never both win (NFR2.3). The signature is never a column nor a parameter here.
 */
@Repository
public class JdbcVerificationStore implements VerificationStore {

    private final JdbcClient jdbc;

    public JdbcVerificationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(IdentityVerification verification) {
        jdbc.sql("""
                        insert into data_subject_verification (request_id, message, issued_at, expires_at, state)
                        values (:id, :message, :issuedAt, :expiresAt, :state)
                        """)
                .param("id", verification.requestId())
                .param("message", verification.message())
                .param("issuedAt", Timestamp.from(verification.issuedAt()))
                .param("expiresAt", Timestamp.from(verification.expiresAt()))
                .param("state", verification.state().name())
                .update();
    }

    @Override
    public Optional<IdentityVerification> find(UUID requestId) {
        return jdbc.sql("""
                        select request_id, message, issued_at, expires_at, state, confirmed_at
                        from data_subject_verification where request_id = :id
                        """)
                .param("id", requestId)
                .query((rs, n) -> new IdentityVerification(
                        rs.getObject("request_id", UUID.class),
                        rs.getString("message"),
                        rs.getTimestamp("issued_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant(),
                        VerificationState.valueOf(rs.getString("state")),
                        Optional.ofNullable(rs.getTimestamp("confirmed_at")).map(Timestamp::toInstant)))
                .optional();
    }

    @Override
    public boolean confirm(UUID requestId, Instant now) {
        return jdbc.sql("""
                                update data_subject_verification set state = 'CONFIRMED', confirmed_at = :now
                                where request_id = :id and state = 'PENDING' and expires_at > :now
                                """)
                        .param("now", Timestamp.from(now))
                        .param("id", requestId)
                        .update()
                == 1;
    }

    @Override
    public boolean expire(UUID requestId) {
        return jdbc.sql("""
                                update data_subject_verification set state = 'EXPIRED'
                                where request_id = :id and state = 'PENDING'
                                """).param("id", requestId).update() == 1;
    }
}
