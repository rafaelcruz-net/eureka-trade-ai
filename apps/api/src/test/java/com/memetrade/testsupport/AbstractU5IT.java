package com.memetrade.testsupport;

import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.identity.web.RequestAuthContext;
import com.memetrade.marketdata.api.TokenId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Base of the U5 integration tests of the API process (routes C4 and C7, commands of the person): the integration base
 * of U2 (real PostgreSQL with every migration up to {@code 0010}, the application login, fixed clock, no scheduled
 * job) plus the market doubles of {@link U5TestBeans} and the test {@link CycleAuthContext} of U7 (a test binds a
 * person on its own thread, falling back to the HTTP request principal). U6 and U7 are real.
 */
@Tag("u5")
@Import({AbstractPostgresIT.FixedClockConfig.class, U5TestBeans.class, AbstractU5IT.ApiContextBeans.class})
public abstract class AbstractU5IT extends AbstractPostgresIT {

    @Autowired
    protected U5MarketData market;

    @Autowired
    protected U5Assessments assessments;

    @Autowired
    protected U5Listing listing;

    @Autowired
    private com.memetrade.audit.infra.AuditOutboxRelay outboxRelay;

    @BeforeEach
    void resetU5State() {
        market.clear();
        assessments.clear();
        listing.clear();
    }

    /** See {@link AbstractU5WorkerIT}: the audit events of the test are relayed so other suites never wait for them. */
    @org.junit.jupiter.api.AfterEach
    void relayTheAuditEventsOfTheTest() {
        U5Outbox.drain(outboxRelay);
    }

    /** A fresh tenant with one BETA_USER person. */
    protected Principal newPerson() {
        TestAccounts.Seeded seeded = TestAccounts.betaUser(jdbc, SyntheticIds.mint());
        return new Principal(seeded.tenantId(), seeded.userId(), Role.BETA_USER);
    }

    /** A second person in the SAME tenant as {@code other}. */
    protected Principal samePersonTenant(Principal other) {
        return U5People.sameTenant(jdbc, other);
    }

    /** A synthetic token (never a real mint). */
    protected static TokenId newToken() {
        return new TokenId("solana", SyntheticIds.mint());
    }

    protected static UUID unknownBot() {
        return UUID.randomUUID();
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class ApiContextBeans {

        @Bean
        @Primary
        public CycleAuthContext cycleAuthContext(RequestAuthContext requests) {
            return new CycleAuthContext(requests);
        }
    }
}
