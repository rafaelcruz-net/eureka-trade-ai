package com.memetrade.identity.infra;

import com.memetrade.identity.domain.account.GuestAccountRepository;
import com.memetrade.identity.domain.account.WalletAlreadyLinkedException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Writes of the account creation (BR3.3). A unique violation is recognised BY THE NAME of the constraint, never by the
 * message of the database (reliability-design.md): {@code uq_wallet_address} becomes
 * {@link WalletAlreadyLinkedException}; any other violation stays a technical failure (5xx).
 */
@Repository
public class JdbcGuestAccountRepository implements GuestAccountRepository {

    static final String WALLET_ADDRESS_CONSTRAINT = "uq_wallet_address";

    private final JdbcClient jdbc;

    public JdbcGuestAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void createGuest(UUID userId, UUID tenantId, String walletAddress, String provider, Instant now) {
        Timestamp at = Timestamp.from(now);
        jdbc.sql("""
                        insert into app_user (user_id, tenant_id, role, status, created_at)
                        values (:id, :tenant, 'BETA_USER', 'ACTIVE', :now)
                        """)
                .param("id", userId)
                .param("tenant", tenantId)
                .param("now", at)
                .update();
        try {
            jdbc.sql("""
                            insert into wallet (wallet_id, user_id, address, provider, verified_at)
                            values (:id, :user, :address, :provider, :now)
                            """)
                    .param("id", UUID.randomUUID())
                    .param("user", userId)
                    .param("address", walletAddress)
                    .param("provider", provider)
                    .param("now", at)
                    .update();
        } catch (DataIntegrityViolationException violation) {
            if (WALLET_ADDRESS_CONSTRAINT.equals(constraintOf(violation))) {
                throw new WalletAlreadyLinkedException(violation);
            }
            throw violation;
        }
    }

    @Override
    public void recordAcceptance(TermsAcceptance acceptance) {
        jdbc.sql("""
                        insert into terms_acceptance (acceptance_id, user_id, tenant_id, terms_version, accepted_at,
                                                      invite_id)
                        values (:id, :user, :tenant, :version, :at, :invite)
                        """)
                .param("id", acceptance.acceptanceId())
                .param("user", acceptance.userId())
                .param("tenant", acceptance.tenantId())
                .param("version", acceptance.termsVersion())
                .param("at", Timestamp.from(acceptance.acceptedAt()))
                .param("invite", acceptance.inviteId())
                .update();
    }

    /** The constraint named by the server in the error, or {@code null} when the driver gives none. */
    static String constraintOf(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException psql) {
                ServerErrorMessage server = psql.getServerErrorMessage();
                return server == null ? null : server.getConstraint();
            }
        }
        return null;
    }
}
