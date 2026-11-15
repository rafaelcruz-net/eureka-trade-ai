package com.memetrade.marketdata;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.memetrade.marketdata.infra.MarketHistoryCleanup;
import com.memetrade.marketdata.infra.coingecko.CallBudget;
import com.memetrade.marketdata.infra.coingecko.CallBudget.Decision;
import com.memetrade.marketdata.infra.coingecko.CallBudget.Kind;
import com.memetrade.marketdata.infra.coingecko.CoinGeckoFixtures;
import com.memetrade.marketdata.infra.coingecko.CoinGeckoHttp;
import com.memetrade.marketdata.infra.coingecko.CoinGeckoMetrics;
import com.memetrade.marketdata.infra.coingecko.CoinGeckoUsageJob;
import com.memetrade.marketdata.infra.coingecko.JdbcUsageStore;
import com.memetrade.testsupport.AbstractPostgresIT;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Step 5 over the real table {@code marketdata_provider_usage} (migration 0017, Testcontainers PostgreSQL): the call is
 * counted before it is made in its own transaction, the count survives a restart, the priority threshold is applied
 * atomically, the provider usage only raises the count, and rows older than 13 months are deleted.
 */
class CoinGeckoBudgetIT extends AbstractPostgresIT {

    @RegisterExtension
    static WireMockExtension wire = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private LockingTaskExecutor locks;

    @Autowired
    private MarketHistoryCleanup cleanup;

    private JdbcUsageStore store;

    @BeforeEach
    void store() {
        store = new JdbcUsageStore(jdbc, transactionManager);
    }

    private CallBudget budget(String source, long monthly) {
        return new CallBudget(source, 250, 2, monthly, store, clock, new SimpleMeterRegistry());
    }

    private long stored(String source, String month) {
        return jdbc.sql("select calls from marketdata_provider_usage where source = :s and month = :m")
                .param("s", source)
                .param("m", month)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    @Test
    void eachAllowedCallIsStoredAndARestartKeepsTheCount() {
        CallBudget first = budget("it-restart", 450_000);
        assertThat(first.acquire(Kind.PRICE)).isEqualTo(Decision.ALLOWED);
        assertThat(first.acquire(Kind.DISCOVERY)).isEqualTo(Decision.ALLOWED);

        CallBudget restarted = budget("it-restart", 450_000);
        restarted.refresh();

        assertThat(stored("it-restart", "2026-10")).isEqualTo(2);
        assertThat(restarted.monthCalls()).isEqualTo(2);
    }

    @Test
    void theCounterCommitsInItsOwnTransactionEvenWhenTheCallerRollsBack() {
        CallBudget budget = budget("it-own-tx", 450_000);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            budget.acquire(Kind.PRICE);
            status.setRollbackOnly();
        });

        assertThat(stored("it-own-tx", "2026-10")).isEqualTo(1);
    }

    @Test
    void theThresholdOfTheKindIsCheckedInTheSameStatementThatCounts() {
        jdbc.sql("insert into marketdata_provider_usage (source, month, calls, updated_at) values"
                        + " ('it-threshold', '2026-10', 90, :now)")
                .param("now", Timestamp.from(clock.instant()))
                .update();
        CallBudget budget = budget("it-threshold", 100);

        assertThat(budget.acquire(Kind.DETAIL)).isEqualTo(Decision.MONTH_BUDGET);
        assertThat(stored("it-threshold", "2026-10"))
                .as("a refused call is not counted")
                .isEqualTo(90);
        assertThat(budget.acquire(Kind.PRICE)).isEqualTo(Decision.ALLOWED);
        assertThat(stored("it-threshold", "2026-10")).isEqualTo(91);
    }

    @Test
    void theHourlyUsageReadingKeepsTheGreatestValue() {
        CallBudget budget = budget("coingecko-it", 450_000);
        budget.acquire(Kind.PRICE);
        wire.stubFor(get(urlPathEqualTo("/api/v3/key")).willReturn(okJson(CoinGeckoFixtures.keyUsage(5_000))));
        CoinGeckoHttp http = new CoinGeckoHttp(
                wire.baseUrl() + "/api/v3",
                "x-cg-pro-api-key",
                CoinGeckoFixtures.TEST_KEY,
                Duration.ofSeconds(2),
                Duration.ofSeconds(3));
        CoinGeckoUsageJob job = new CoinGeckoUsageJob(
                http, budget, locks, new CoinGeckoMetrics(new SimpleMeterRegistry(), "coingecko-it"), clock);

        assertThat(job.runLocked()).hasValue(5_000);
        assertThat(stored("coingecko-it", "2026-10")).isEqualTo(5_000);

        wire.stubFor(get(urlPathEqualTo("/api/v3/key")).willReturn(okJson(CoinGeckoFixtures.keyUsage(10))));
        assertThat(job.runLocked())
                .as("a lower provider value never lowers the count")
                .hasValue(5_001);
    }

    @Test
    void theHistoryCleanupKeepsThirteenMonthsOfCounters() {
        for (String month : new String[] {"2025-09", "2025-10", "2026-10"}) {
            jdbc.sql("insert into marketdata_provider_usage (source, month, calls, updated_at)"
                            + " values ('it-retention', :m, 1, :now) on conflict do nothing")
                    .param("m", month)
                    .param("now", Timestamp.from(clock.instant()))
                    .update();
        }

        cleanup.runLocked();

        assertThat(jdbc.sql("select month from marketdata_provider_usage where source = 'it-retention' order by 1")
                        .query(String.class)
                        .list())
                .containsExactly("2025-10", "2026-10");
    }
}
