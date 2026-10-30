package com.memetrade.identity.infra;

import com.memetrade.shared.wallet.WalletAddress;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * EXISTS ONLY WITH THE "test" PROFILE (infrastructure-specification.md): on start-up it creates a tenant, a
 * BETA_USER account and the PUBLIC wallet of the generated smoke wallet (SMOKE_BETA_WALLET_ADDRESS), because U1 has
 * no invitations yet (OQ-8). It never receives a key; it logs a WARN with the masked address.
 */
@Component
@Profile("test")
public class SmokeAccountSeeder {

    private static final Logger log = LoggerFactory.getLogger(SmokeAccountSeeder.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final String walletAddress;

    public SmokeAccountSeeder(
            JdbcClient jdbc,
            TransactionTemplate transactions,
            Clock clock,
            @Value("${memetrade.smoke.beta-wallet-address:}") String walletAddress) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
        this.walletAddress = walletAddress;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        if (!WalletAddress.isValid(walletAddress)) {
            throw new IllegalStateException(
                    "The test profile needs SMOKE_BETA_WALLET_ADDRESS (a public wallet address)");
        }
        WalletAddress wallet = WalletAddress.of(walletAddress);
        Boolean created = transactions.execute(status -> insertIfAbsent(wallet));
        log.warn(
                "TEST PROFILE ACTIVE: smoke BETA_USER account {} for wallet {}",
                Boolean.TRUE.equals(created) ? "created" : "already present",
                wallet);
    }

    private boolean insertIfAbsent(WalletAddress wallet) {
        Integer existing = jdbc.sql("select count(*) from wallet where address = :a")
                .param("a", wallet.full())
                .query(Integer.class)
                .single();
        if (existing != null && existing > 0) {
            return false;
        }
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.sql(
                        "insert into tenant (tenant_id, name, status, created_at) values (:id, 'Smoke test tenant', 'ACTIVE', :now)")
                .param("id", tenantId)
                .param("now", now)
                .update();
        jdbc.sql("insert into app_user (user_id, tenant_id, role, status, created_at)"
                        + " values (:id, :tenant, 'BETA_USER', 'ACTIVE', :now)")
                .param("id", userId)
                .param("tenant", tenantId)
                .param("now", now)
                .update();
        jdbc.sql("insert into wallet (wallet_id, user_id, address, provider, verified_at)"
                        + " values (:id, :user, :address, 'PHANTOM', :now)")
                .param("id", UUID.randomUUID())
                .param("user", userId)
                .param("address", wallet.full())
                .param("now", now)
                .update();
        return true;
    }
}
