package com.memetrade.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.api.AuthContext;
import com.memetrade.identity.api.Principal;
import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.testsupport.AbstractU6IT;
import com.memetrade.testsupport.ControllableMarketData;
import com.memetrade.testsupport.CycleAuthContext;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/**
 * Passo 2 of U6 (NFR12.1): the integration base of the Unit starts with the doubles and helpers the test-first steps
 * use, and the Unit-scoped command ({@code -Dgroups=u6}) runs it.
 */
class U6ContextIT extends AbstractU6IT {

    @Autowired
    private Environment environment;

    @Autowired
    private MarketDataQuery marketData;

    @Autowired
    private AuthContext auth;

    @Test
    void theContextStartsWithTheU6DoublesAndThirteenSchedulerThreads() {
        // U5: 14 since the live channel poller of the api (RuntimeSettingsGuard.MIN_SCHEDULER_POOL).
        assertThat(environment.getProperty("spring.task.scheduling.pool.size")).isEqualTo("14");
        assertThat(marketData).isInstanceOf(ControllableMarketData.class);
        assertThat(auth).isInstanceOf(CycleAuthContext.class);
    }

    @Test
    void theCycleTransactionIsReadCommittedAndBindsTheOwner() {
        Principal person = newPerson();

        String isolation = inCycle(person, () -> {
            assertThat(auth.current()).isEqualTo(person);
            return jdbc.sql("show transaction_isolation").query(String.class).single();
        });

        assertThat(isolation).isEqualTo("read committed");
    }

    @Test
    void theMarketDoubleScriptsPriceLiquidityAgeAndUnknown() {
        TokenId token = newToken();
        marketNow(token, "1.000000000000", "10000.000000");
        market.liquidity(token, observedAgo("10000.000000", Duration.ofSeconds(61)));

        assertThat(marketData.referencePrice(token)).isInstanceOf(MarketValue.Known.class);
        assertThat(((MarketValue.Known) marketData.liquidity(token)).observedAt())
                .isEqualTo(clock.instant().minusSeconds(61));
        market.price(token, ControllableMarketData.unknown());
        assertThat(marketData.referencePrice(token)).isInstanceOf(MarketValue.Unknown.class);
    }

    @Test
    void aFundedBotHasAPaperAccountOfTheRealU7AndTheExtraLoginsConnect() {
        Principal person = newPerson();
        UUID bot = fundedBot(person, "1000.000000");

        assertThat(jdbc.sql("select count(*) from paper_account where bot_id = :b")
                        .param("b", bot)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        assertThat(appRole().sql("select current_user").query(String.class).single())
                .isEqualTo(APP.user());
        assertThat(retentionRole()
                        .sql("select current_user")
                        .query(String.class)
                        .single())
                .isEqualTo(RETENTION.user());
    }
}
