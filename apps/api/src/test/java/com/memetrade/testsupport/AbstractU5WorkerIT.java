package com.memetrade.testsupport;

import com.memetrade.MemetradeApplication;
import com.memetrade.identity.api.Principal;
import com.memetrade.marketdata.api.TokenId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base of the U5 integration tests of the WORKER process (cycle, midnight, closing check, purge, system pause), built
 * like the worker of docker-compose.yml: profile {@code worker}, {@code memetrade.process=worker}, the same PostgreSQL
 * container and migrations as {@link AbstractPostgresIT}, and NO web secret at all (no signing key, no app origin, no
 * trusted proxies, no administrator wallets, no retention login). The authentication context is the production
 * {@code bots.infra.CycleAuthContext}; U6 and U7 are real, only C11, C12 and the scanner are doubles
 * ({@link U5TestBeans}). Scheduled jobs are off: the tests drive the jobs directly with the {@link MutableClock}.
 */
@Tag("u5")
@SpringBootTest(
        classes = MemetradeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {"memetrade.process=worker", "memetrade.scheduling.enabled=false"})
@ActiveProfiles("worker")
@Import({U5TestBeans.class, AbstractU5WorkerIT.WorkerClock.class})
public abstract class AbstractU5WorkerIT {

    static {
        // Starts and migrates the shared container BEFORE the Spring context of the worker: the migration runner is a
        // Spring application of its own and must not reconfigure the logging of this context after it started.
        AbstractPostgresIT.POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", AbstractPostgresIT.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", AbstractPostgresIT.APP::user);
        registry.add("spring.datasource.password", AbstractPostgresIT.APP::password);
    }

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected U5MarketData market;

    @Autowired
    protected U5Assessments assessments;

    @Autowired
    protected U5Listing listing;

    @Autowired
    protected U5Faults faults;

    @Autowired
    protected com.memetrade.bots.infra.CycleAuthContext cycleAuth;

    @Autowired
    protected com.memetrade.bots.application.BotCommandService commands;

    @Autowired
    protected com.memetrade.bots.infra.JdbcBotRepository bots;

    @Autowired
    private com.memetrade.audit.infra.AuditOutboxRelay outboxRelay;

    /** The real commands, each run as the owner bound in the production cycle authentication context. */
    protected U5Bots fixtures;

    /**
     * The U5 tests write many audit events with the fixed clock of the past; the relay of the other suites reads one
     * batch of 100 by creation time, so the events of U5 are relayed here and never starve their batch.
     */
    @org.junit.jupiter.api.AfterEach
    void relayTheAuditEventsOfTheTest() {
        U5Outbox.drain(outboxRelay);
    }

    @BeforeEach
    void resetWorkerState() {
        clock.set(MutableClock.DEFAULT_START);
        market.clear();
        assessments.clear();
        listing.clear();
        faults.clear();
        fixtures = new U5Bots(commands, bots, cycleAuth::as);
    }

    protected Principal newPerson() {
        return U5People.newPerson(jdbc);
    }

    protected Principal samePersonTenant(Principal other) {
        return U5People.sameTenant(jdbc, other);
    }

    protected static TokenId newToken() {
        return new TokenId("solana", SyntheticIds.mint());
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class WorkerClock {
        @Bean
        @Primary
        public MutableClock mutableClock() {
            return new MutableClock();
        }

        @Bean
        public U5Faults u5Faults() {
            return new U5Faults();
        }

        /** The real barrier of U6 behind the failure injection (NFR10.2). */
        @Bean
        @Primary
        public com.memetrade.riskcontrol.api.OrderGate faultyOrderGate(
                @org.springframework.beans.factory.annotation.Qualifier("orderGate")
                        com.memetrade.riskcontrol.api.OrderGate real,
                U5Faults faults) {
            return faults.gate(real);
        }

        /** The real execution of U7 behind the failure injection (NFR10.2). */
        @Bean
        @Primary
        public com.memetrade.ledger.api.PaperExecution faultyPaperExecution(
                @org.springframework.beans.factory.annotation.Qualifier("paperExecutionImpl")
                        com.memetrade.ledger.api.PaperExecution real,
                U5Faults faults) {
            return faults.execution(real);
        }

        /** The real read model of U7 behind the failure injection (NFR10.2). */
        @Bean
        @Primary
        public com.memetrade.ledger.api.LedgerReadModel faultyLedgerReadModel(
                @org.springframework.beans.factory.annotation.Qualifier("ledgerReadModelImpl")
                        com.memetrade.ledger.api.LedgerReadModel real,
                U5Faults faults) {
            return faults.ledger(real);
        }
    }
}
