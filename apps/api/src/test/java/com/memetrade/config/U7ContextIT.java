package com.memetrade.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.identity.api.AuthContext;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.UnauthenticatedException;
import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.testsupport.AbstractU7IT;
import com.memetrade.testsupport.ControllableMarketData;
import com.memetrade.testsupport.CycleAuthContext;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/** Passo 2: the U7 command runs green before the first red test; the doubles of the U7 base behave as documented. */
class U7ContextIT extends AbstractU7IT {

    @Autowired
    private Environment environment;

    @Autowired
    private MarketDataQuery marketData;

    @Autowired
    private AuthContext auth;

    @Test
    void theContextStartsWithTheSchedulerThreadsOfU6AndTheControllableMarketData() {
        // U5: 14 since the live channel poller of the api (RuntimeSettingsGuard.MIN_SCHEDULER_POOL).
        assertThat(environment.getProperty("spring.task.scheduling.pool.size")).isEqualTo("14");
        assertThat(marketData).isInstanceOf(ControllableMarketData.class);
        assertThat(auth).isInstanceOf(CycleAuthContext.class);
    }

    @Test
    void theMarketDoubleAnswersKnownUnknownAndStaleValues() {
        TokenId token = newToken();
        market.set(token, "1.000000", "100000.000000", 6);
        assertThat(((MarketValue.Known) marketData.referencePrice(token)).value())
                .isEqualByComparingTo(new BigDecimal("1"));
        assertThat(((MarketValue.Known) marketData.decimals(token)).value().intValueExact())
                .isEqualTo(6);

        market.price(token, ControllableMarketData.unknown());
        assertThat(marketData.referencePrice(token).isKnown()).isFalse();

        market.observedAgo(token, "1", "1", 6, Duration.ofSeconds(301));
        assertThat(((MarketValue.Known) marketData.referencePrice(token)).stale())
                .isTrue();
    }

    @Test
    void anUnscriptedTokenFallsBackToTheRealQueryAndIsUnknownNeverZero() {
        TokenId never = newToken();
        assertThat(marketData.referencePrice(never).isKnown()).isFalse();
        assertThat(marketData.decimals(never).isKnown()).isFalse();
    }

    @Test
    void theCyclePrincipalIsBoundOnlyInsideTheCycle() {
        Principal person = newPerson();
        assertThatThrownBy(auth::current).isInstanceOf(UnauthenticatedException.class);
        Principal seen = inCycle(person, auth::current);
        assertThat(seen).isEqualTo(person);
        assertThat(auth.optional()).isEmpty();
    }

    @Autowired
    private org.springframework.context.ApplicationContext context;

    /** The only AuthContext of a normal run is the request one (plus the test double of the cycle here). */
    @Test
    void outsideTheLoadtestProfileNoOtherAuthContextExists() {
        assertThat(context.getBeansOfType(AuthContext.class)).hasSize(2).containsValue(auth);
        assertThat(environment.getActiveProfiles()).doesNotContain("loadtest");
    }

    /** Passo 23: no bean of the load cycle driver package exists in the test context (only with loadtest). */
    @Test
    void outsideTheLoadtestProfileNoLoadDriverBeanExists() {
        java.util.List<String> loadBeans = java.util.Arrays.stream(context.getBeanDefinitionNames())
                .map(context::getType)
                .filter(java.util.Objects::nonNull)
                .map(Class::getName)
                .filter(name -> name.startsWith("com.memetrade.ledger.loadtest."))
                .toList();
        assertThat(loadBeans).isEmpty();
    }

    @Test
    void theApplicationLoginConnectionIsNotTheOwner() {
        assertThat(appRole().sql("select current_user").query(String.class).single())
                .isEqualTo(APP.user());
    }
}
