package com.memetrade.marketdata.infra.coingecko;

import com.memetrade.marketdata.domain.MarketStore;
import com.memetrade.marketdata.infra.MarketCollectorJob;
import com.memetrade.marketdata.infra.MarketDataProperties;
import com.memetrade.shared.config.ProcessRole;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wiring of the real source {@code coingecko} (261113-trading-real-bot U2): WORKER only and only when
 * {@code MARKET_SOURCES} names coingecko. The api never builds the client nor reads the key (NFR1.6); it only knows the
 * source for {@code activeSourceKind()}, {@code attribution()} and the start-up lock.
 *
 * <p>The API key is read here, and only here, from the environment variable {@value #API_KEY_VARIABLE} (BR1.10): a
 * missing key stops the worker with a message that names the variable, never a value. Outside the test profile the
 * base URL must be HTTPS on a CoinGecko host.
 */
@Configuration
@ProcessRole(ProcessRole.Kind.WORKER)
@Conditional(CoinGeckoConfiguredCondition.class)
public class CoinGeckoConfig {

    public static final String API_KEY_VARIABLE = "COINGECKO_API_KEY";

    @Bean
    public CoinGeckoMetrics coinGeckoMetrics(MeterRegistry meters) {
        return new CoinGeckoMetrics(meters, MarketDataProperties.COINGECKO);
    }

    @Bean
    public CoinGeckoHttp coinGeckoHttp(CoinGeckoProperties properties, Environment environment) {
        if (!environment.acceptsProfiles(Profiles.of("test"))) {
            properties.checkProductionBaseUrl();
        }
        return new CoinGeckoHttp(
                properties.baseUrl(),
                properties.apiKeyHeader(),
                apiKey(environment),
                properties.connectTimeout(),
                properties.callTimeout());
    }

    /** BR1.10: the key comes only from the environment of the worker; its value never reaches a log or a message. */
    static String apiKey(Environment environment) {
        String key = environment.getProperty(API_KEY_VARIABLE);
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(API_KEY_VARIABLE
                    + " is required by the worker when MARKET_SOURCES contains coingecko (secret coingecko-api-key)");
        }
        return key.trim();
    }

    @Bean
    public CallBudget coinGeckoCallBudget(
            CoinGeckoProperties properties,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            Clock clock,
            MeterRegistry meters) {
        CallBudget budget = new CallBudget(
                MarketDataProperties.COINGECKO,
                properties.callsPerMinute(),
                properties.detailCallsPerMinute(),
                properties.monthlyCallBudget(),
                new JdbcUsageStore(jdbc, transactionManager),
                clock,
                meters);
        budget.refresh();
        return budget;
    }

    @Bean
    public CoinGeckoAdapter coinGeckoAdapter(
            CoinGeckoProperties properties,
            CoinGeckoHttp http,
            CallBudget budget,
            Clock clock,
            CoinGeckoMetrics metrics) {
        return new CoinGeckoAdapter(properties, http, budget, clock, metrics);
    }

    @Bean
    public CoinGeckoCollectorJob coinGeckoCollectorJob(
            MarketCollectorJob.Settings settings,
            CoinGeckoAdapter adapter,
            MarketStore store,
            MarketDataProperties properties,
            MeterRegistry meters) {
        return new CoinGeckoCollectorJob(settings, adapter, store, properties, meters);
    }

    @Bean
    public CoinGeckoUsageJob coinGeckoUsageJob(
            CoinGeckoHttp http, CallBudget budget, LockingTaskExecutor locks, CoinGeckoMetrics metrics, Clock clock) {
        return new CoinGeckoUsageJob(http, budget, locks, metrics, clock);
    }
}
