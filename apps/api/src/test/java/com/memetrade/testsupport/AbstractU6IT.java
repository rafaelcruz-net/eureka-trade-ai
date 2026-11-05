package com.memetrade.testsupport;

import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.ledger.api.PaperExecution;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.TokenId;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Base of the U6 integration tests: the integration base of U2 (real PostgreSQL with every migration including
 * {@code 0009}, {@code 10-roles.sql} applied, the application login, fixed clock) with the same test beans as U7, so
 * the Spring context is shared with the U7 tests:
 *
 * <ul>
 *   <li>{@link ControllableMarketData}: the C11 double (price, liquidity, age and {@code unknown} on command);
 *   <li>{@link CycleAuthContext}: the owner of the bot bound by the test, as U5 will bind it (C10);
 *   <li>{@link #inCycle}: the cycle transaction opened as U5 will open it ({@code TransactionTemplate} in
 *       {@code READ COMMITTED}, Decisões de implementação of the plan);
 *   <li>{@link #fundedBot}: a PAPER account created through the real {@code PaperExecution.fund} of U7;
 *   <li>{@link #appRole()} and {@link #retentionRole()}: extra connections with the application and the retention
 *       login for the privilege tests.
 * </ul>
 *
 * <p>Every test creates its own people, bots, decisions and synthetic mints; nothing real, nothing shared.
 */
@Tag("u6")
@Import({AbstractPostgresIT.FixedClockConfig.class, AbstractU7IT.U7TestConfig.class})
public abstract class AbstractU6IT extends AbstractPostgresIT {

    @Autowired
    protected ControllableMarketData market;

    @Autowired
    protected PaperExecution paperExecution;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private org.springframework.context.ApplicationContext context;

    protected TransactionTemplate cycleTransactions;

    protected PaperDriver paper;

    /** Limits, orders, row counts and gate services with replaced collaborators (built from the real beans). */
    protected com.memetrade.riskcontrol.RiskGateFixtures gates;

    @BeforeEach
    void resetU6State() {
        market.clear();
        cycleTransactions = cycleTransactions(transactionManager);
        paper = new PaperDriver(paperExecution, cycleTransactions);
        gates = new com.memetrade.riskcontrol.RiskGateFixtures(
                context.getBean(com.memetrade.marketdata.api.MarketDataQuery.class),
                context.getBean(com.memetrade.pnl.api.PortfolioRiskView.class),
                context.getBean(com.memetrade.riskcontrol.domain.EvaluationRepository.class),
                context.getBean(com.memetrade.riskcontrol.domain.RejectionRepository.class),
                context.getBean(com.memetrade.riskcontrol.domain.RejectionEvents.class),
                context.getBean(com.memetrade.identity.api.AuthContext.class),
                context.getBean(java.time.Clock.class),
                context.getBean(com.memetrade.riskcontrol.domain.LimitChecks.class),
                context.getBean(com.memetrade.riskcontrol.domain.EvaluationErrors.class));
    }

    /** The cycle transaction of U5: a new transaction in READ COMMITTED (the PostgreSQL default, made explicit). */
    public static TransactionTemplate cycleTransactions(PlatformTransactionManager manager) {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
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

    /** A bot of {@code person} with a PAPER account funded through the real {@code PaperExecution.fund} of U7. */
    protected UUID fundedBot(Principal person, String amount) {
        return paper.fundedBot(person, amount);
    }

    /** Runs {@code work} in a cycle transaction of {@code person} (READ COMMITTED), the way U5 will call the gate. */
    protected <T> T inCycle(Principal person, Supplier<T> work) {
        return CycleAuthContext.as(person, () -> cycleTransactions.execute(status -> work.get()));
    }

    /** Price, liquidity and decimals of {@code token} known and observed now (the catalogue base). */
    protected void marketNow(TokenId token, String price, String liquidity) {
        market.set(token, price, liquidity, 6);
    }

    /** A known value observed {@code age} ago (flagged stale by MarketData only above 5 minutes). */
    protected MarketValue.Known observedAgo(String value, Duration age) {
        return new MarketValue.Known(
                new BigDecimal(value),
                ControllableMarketData.SOURCE,
                clock.instant().minus(age),
                age.compareTo(Duration.ofMinutes(5)) > 0);
    }

    /** The application login on its own connection (auto-commit), for the database privilege tests. */
    protected static JdbcClient appRole() {
        return connect(APP);
    }

    /** The retention login (member of audit_retention and, since U6, of risk_retention) on its own connection. */
    protected static JdbcClient retentionRole() {
        return connect(RETENTION);
    }

    /** The schema owner login (only migrations use it in production). */
    protected static JdbcClient ownerRole() {
        return connect(OWNER);
    }
}
