package com.memetrade.marketdata.infra;

import com.memetrade.marketdata.api.MarketSourceAdapter;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.api.WatchedTokens;
import com.memetrade.marketdata.domain.DivergencePolicy;
import com.memetrade.marketdata.domain.FieldResolver;
import com.memetrade.marketdata.domain.MarketCollector;
import com.memetrade.marketdata.domain.MarketStore;
import com.memetrade.marketdata.domain.SourceFetcher;
import com.memetrade.marketdata.infra.simulated.SimulatedMarketSource;
import com.memetrade.shared.config.RecordedSwapFixture;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Executor;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wiring of MarketData (U4): only the simulated adapters of {@code MARKET_SOURCES} exist in the beta (C-06); real
 * adapters arrive behind the same port after G0 and SP-1.
 */
@Configuration
public class MarketDataConfig {

    /** Threads that run the source calls, so the time limit can cancel a slow call (reliability-design.md). */
    public static final String SOURCE_CALLS_EXECUTOR = "marketSourceCalls";

    @Bean
    public ConfiguredSources configuredSources(
            MarketDataProperties properties,
            Clock clock,
            org.springframework.core.env.Environment environment,
            Optional<RecordedSwapFixture> recordedSwap) {
        SimulatedMarketSource.Scenario scenario = properties.simulated().scenario();
        boolean testProfile = environment.acceptsProfiles(org.springframework.core.env.Profiles.of("test"));
        // U5: the frequent-signal scenario exists only for the load scenario u5, never outside the test profile.
        if (scenario != SimulatedMarketSource.Scenario.STANDARD && !testProfile) {
            throw MarketDataProperties.invalid("MARKET_SIMULATED_SCENARIO", "STANDARD outside the test profile");
        }
        // 261113-trading-real-bot U4: the recorded swap exists only in the test profile (RecordedSwapFixtureConfig);
        // checked again here so no other profile ever publishes a pinned token.
        if (recordedSwap.isPresent() && !testProfile) {
            throw MarketDataProperties.invalid("RECORDED_MINT", "empty outside the test profile");
        }
        List<SimulatedMarketSource.Pinned> pinned =
                recordedSwap.map(MarketDataConfig::recordedSwapTokens).orElse(List.of());
        // U2: the real source coingecko has no adapter here; only the worker builds it (CoinGeckoConfig), so the api
        // never calls the provider (NFR1.6). The simulated sources keep being collected by the api, as before.
        return new ConfiguredSources(properties.sources().stream()
                .filter(MarketDataProperties.SIMULATED_SOURCES::contains)
                .map(id -> (MarketSourceAdapter) SimulatedMarketSource.forSource(
                        id,
                        properties.simulated().seed(),
                        properties.simulated().tokenCount(),
                        clock,
                        scenario,
                        RECORDED_SWAP_SOURCE.equals(id) ? pinned : List.of()))
                .toList());
    }

    /** U4: the simulated source that publishes the tokens of the recorded swap. */
    static final String RECORDED_SWAP_SOURCE = "sim-a";

    /**
     * U4 (infrastructure-specification.md "Decisão do bot", test profile only): the wrapped SOL and the recorded mint,
     * with fresh fixed values in {@code sim-a}. The prices match the recorded quote ({@link RecordedSwapFixture#SOL_USD}
     * and {@link RecordedSwapFixture#tokenUsd()}); liquidity, volume and holders are high enough for an available
     * assessment (LIQUIDITY, HOLDERS and VOLUME_24H known: 80% coverage, the minimum of weight version 1).
     */
    static List<SimulatedMarketSource.Pinned> recordedSwapTokens(RecordedSwapFixture fixture) {
        BigDecimal liquidity = new BigDecimal("2500000.00");
        BigDecimal volume = new BigDecimal("8000000.00");
        BigDecimal holders = BigDecimal.valueOf(250_000);
        Duration age = Duration.ofDays(30);
        return List.of(
                new SimulatedMarketSource.Pinned(
                        new MarketSourceAdapter.TokenInfo(
                                new TokenId(SimulatedMarketSource.CHAIN, RecordedSwapFixture.WRAPPED_SOL),
                                "SOL",
                                "Wrapped SOL (simulado)",
                                RecordedSwapFixture.WRAPPED_SOL_DECIMALS),
                        RecordedSwapFixture.SOL_USD,
                        liquidity,
                        volume,
                        holders,
                        age),
                new SimulatedMarketSource.Pinned(
                        new MarketSourceAdapter.TokenInfo(
                                new TokenId(SimulatedMarketSource.CHAIN, fixture.mint()),
                                "SWAP",
                                "Mint do swap gravado (simulado)",
                                fixture.decimals()),
                        fixture.tokenUsd(),
                        liquidity,
                        volume,
                        holders,
                        age));
    }

    /** Executor {@code market-source-calls}: 2 threads, one per simulated source (infrastructure-specification.md). */
    @Bean(SOURCE_CALLS_EXECUTOR)
    public ThreadPoolTaskExecutor marketSourceCalls() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("market-source-calls-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(8);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    @Bean
    public SourceFetcher sourceCaller(
            @Qualifier(SOURCE_CALLS_EXECUTOR) ThreadPoolTaskExecutor calls,
            MarketDataProperties properties,
            MeterRegistry meters) {
        return new SourceCaller(
                calls.getThreadPoolExecutor(),
                Sleeper.REAL,
                new Random(properties.simulated().seed()),
                SourceCaller.TIMEOUT,
                java.util.Map.of(MarketDataProperties.COINGECKO, SourceCaller.COINGECKO_POLICY),
                (sourceId, attempt) -> Counter.builder("market_collection_retries_total")
                        .tag("source", sourceId)
                        .register(meters)
                        .increment());
    }

    /**
     * U2 (BR1.1): the tokens other modules need priced by the real source. Empty here; U4 replaces this bean with the
     * REAL positions behind the same {@link WatchedTokens} interface.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(WatchedTokens.class)
    public WatchedTokens watchedTokens() {
        return WatchedTokens.NONE;
    }

    @Bean
    public MarketCollector marketCollector(
            MarketStore store,
            SourceFetcher fetcher,
            TransactionTemplate transactions,
            Clock clock,
            WatchedTokens watched) {
        return new MarketCollector(store, fetcher, new TransactionUnitOfWork(transactions), clock, watched);
    }

    @Bean
    public MarketSourceRegistry marketSourceRegistry(
            ConfiguredSources sources, MarketStore store, MarketDataProperties properties) {
        return new MarketSourceRegistry(sources.adapters(), store, properties);
    }

    /** Collections run on the scheduler pool when scheduling is on; tests call the job directly. */
    @Bean
    public MarketCollectorJob.Settings marketCollectorSettings(
            ConfiguredSources sources,
            MarketCollector collector,
            MarketStore store,
            MarketSourceRegistry registry,
            LockingTaskExecutor locks,
            ObjectProvider<TaskScheduler> scheduler,
            ApplicationEventPublisher events,
            MarketDataProperties properties,
            Clock clock) {
        Executor executor = task -> {
            TaskScheduler available = scheduler.getIfAvailable();
            if (available == null) {
                task.run();
            } else {
                available.schedule(task, clock.instant());
            }
        };
        return new MarketCollectorJob.Settings(
                sources.adapters(),
                collector,
                store,
                registry,
                locks,
                executor,
                events,
                properties.collectInterval(),
                clock);
    }

    @Bean
    public FieldResolver fieldResolver(MarketDataProperties properties) {
        return new FieldResolver(DivergencePolicy.uniform(properties.divergenceTolerancePercent()));
    }
}
