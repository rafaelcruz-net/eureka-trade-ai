package com.memetrade.identity.infra;

import com.memetrade.identity.domain.terms.CurrentTerms;
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
 *
 * <p>u3-reacceptance (infrastructure-specification.md "Dados de teste para a trava"): the smoke account also gets a
 * {@code REACCEPTANCE} of the EFFECTIVE current version of the terms (no invitation, as the cross CHECK allows), so the
 * reacceptance lock lets the existing smoke specs through. That step runs OUTSIDE the early return of the account
 * creation: a start with the account already present still records the acceptance that is missing, and a second run
 * records nothing ({@code ON CONFLICT DO NOTHING} on {@code (user_id, terms_version)}).
 */
@Component
@Profile("test")
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class SmokeAccountSeeder {

    private static final Logger log = LoggerFactory.getLogger(SmokeAccountSeeder.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final CurrentTerms terms;
    private final String walletAddress;

    public SmokeAccountSeeder(
            JdbcClient jdbc,
            TransactionTemplate transactions,
            Clock clock,
            CurrentTerms terms,
            @Value("${memetrade.smoke.beta-wallet-address:}") String walletAddress) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
        this.terms = terms;
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
        String version = terms.current().version();
        Boolean accepted = transactions.execute(status -> acceptCurrentIfAbsent(wallet, version));
        log.warn(
                "TEST PROFILE ACTIVE: smoke BETA_USER account {} for wallet {}; terms {} {}",
                Boolean.TRUE.equals(created) ? "created" : "already present",
                wallet,
                version,
                Boolean.TRUE.equals(accepted) ? "accepted" : "already accepted");
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

    /** The REACCEPTANCE of {@code version} for the account of the smoke wallet, only when it is missing. */
    private boolean acceptCurrentIfAbsent(WalletAddress wallet, String version) {
        int inserted = jdbc.sql("""
                        insert into terms_acceptance
                            (acceptance_id, user_id, tenant_id, terms_version, accepted_at, invite_id, source)
                        select :id, u.user_id, u.tenant_id, :version, :now, null, 'REACCEPTANCE'
                        from wallet w join app_user u on u.user_id = w.user_id
                        where w.address = :address
                        on conflict on constraint uq_terms_acceptance_user_version do nothing
                        """)
                .param("id", UUID.randomUUID())
                .param("version", version)
                .param("now", Timestamp.from(clock.instant()))
                .param("address", wallet.full())
                .update();
        return inserted == 1;
    }
}
