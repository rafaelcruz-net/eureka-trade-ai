package com.memetrade.testsupport;

import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.identity.web.RequestAuthContext;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.infra.JdbcMarketDataQuery;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Base of the U7 integration tests: the integration base of U2 (real PostgreSQL with every migration, the application
 * login, fixed clock) plus
 *
 * <ul>
 *   <li>{@link ControllableMarketData} as the C11 the ledger reads (price, liquidity, decimals, unknown, stale);
 *   <li>{@link CycleAuthContext}: the person of the bot cycle, bound by the test as U5 will bind it (C10);
 *   <li>{@link #inCycle}: the cycle transaction opened with a {@code TransactionTemplate}, as U5 will open it;
 *   <li>{@link #appRole()}: an extra connection with the application login for the privilege tests.
 * </ul>
 *
 * <p>Every test creates its own people, bots and synthetic mints; nothing real, nothing shared.
 */
@Tag("u7")
@Import({AbstractPostgresIT.FixedClockConfig.class, AbstractU7IT.U7TestConfig.class})
public abstract class AbstractU7IT extends AbstractPostgresIT {

    @Autowired
    protected ControllableMarketData market;

    @Autowired
    protected TransactionTemplate transactions;

    @Autowired
    private com.memetrade.pnl.web.ReconciliationRunLimiter reconciliationRunLimiter;

    @BeforeEach
    void resetU7State() {
        market.clear();
        reconciliationRunLimiter.clear();
    }

    /** A fresh tenant with one BETA_USER person, as the principal of the authentication context sees it. */
    protected Principal newPerson() {
        TestAccounts.Seeded seeded = TestAccounts.betaUser(jdbc, SyntheticIds.mint());
        return new Principal(seeded.tenantId(), seeded.userId(), Role.BETA_USER);
    }

    /** A second person in the SAME tenant as {@code other}. */
    protected Principal samePersonTenant(Principal other) {
        UUID userId = UUID.randomUUID();
        jdbc.sql("""
                        insert into app_user (user_id, tenant_id, role, status, created_at)
                        values (:id, :tenant, 'BETA_USER', 'ACTIVE', now())
                        """).param("id", userId).param("tenant", other.tenantId()).update();
        return new Principal(other.tenantId(), userId, Role.BETA_USER);
    }

    /** A synthetic token (never a real mint). */
    protected static TokenId newToken() {
        return new TokenId("solana", SyntheticIds.mint());
    }

    /** Runs {@code work} in a cycle transaction of {@code person}, the way U5 will call the ledger. */
    protected <T> T inCycle(Principal person, Supplier<T> work) {
        return CycleAuthContext.as(person, () -> transactions.execute(status -> work.get()));
    }

    /** The application login on its own connection (auto-commit), for the database privilege tests. */
    protected static JdbcClient appRole() {
        return connect(APP);
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class U7TestConfig {

        @Bean
        @Primary
        public ControllableMarketData controllableMarketData(JdbcMarketDataQuery real, Clock clock) {
            return new ControllableMarketData(real, clock);
        }

        @Bean
        @Primary
        public CycleAuthContext cycleAuthContext(RequestAuthContext requests) {
            return new CycleAuthContext(requests);
        }
    }
}
