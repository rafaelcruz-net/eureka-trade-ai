package com.memetrade.identity.infra;

import com.memetrade.identity.domain.account.WalletAlreadyLinkedException;
import com.memetrade.identity.domain.login.AdminAccounts;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Writes the AdminAccount of the first administrator entry (entities.md, BR1.2): one {@code app_user} with role ADMIN in
 * the system tenant and its {@code wallet}. A unique violation of the wallet is recognised BY THE NAME of the
 * constraint, like the account creation of U3.
 */
@Repository
public class JdbcAdminAccounts implements AdminAccounts {

    private final JdbcClient jdbc;

    public JdbcAdminAccounts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void createAdmin(UUID userId, UUID tenantId, String walletAddress, String provider, Instant now) {
        Timestamp at = Timestamp.from(now);
        jdbc.sql("""
                        insert into app_user (user_id, tenant_id, role, status, created_at)
                        values (:id, :tenant, 'ADMIN', 'ACTIVE', :now)
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
            if (JdbcGuestAccountRepository.WALLET_ADDRESS_CONSTRAINT.equals(
                    JdbcGuestAccountRepository.constraintOf(violation))) {
                throw new WalletAlreadyLinkedException(violation);
            }
            throw violation;
        }
    }
}
