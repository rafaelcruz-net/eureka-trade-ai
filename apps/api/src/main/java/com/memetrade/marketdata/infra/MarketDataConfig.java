package com.memetrade.marketdata.infra;

import com.memetrade.marketdata.api.MarketSourceAdapter;
import com.memetrade.marketdata.domain.DivergencePolicy;
import com.memetrade.marketdata.domain.FieldResolver;
import com.memetrade.marketdata.domain.MarketCollector;
import com.memetrade.marketdata.domain.MarketStore;
import com.memetrade.marketdata.domain.SourceFetcher;
import com.memetrade.marketdata.infra.simulated.SimulatedMarketSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
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
            MarketDataProperties properties, Clock clock, org.springframework.core.env.Environment environment) {
        SimulatedMarketSource.Scenario scenario = properties.simulated().scenario();
        // U5: the frequent-signal scenario exists only for the load scenario u5, never outside the test profile.
        if (scenario != SimulatedMarketSource.Scenario.STANDARD
                && !environment.acceptsProfiles(org.springframework.core.env.Profiles.of("test"))) {
            throw MarketDataProperties.invalid("MARKET_SIMULATED_SCENARIO", "STANDARD outside the test profile");
        }
        return new ConfiguredSources(properties.sources().stream()
                .map(id -> (MarketSourceAdapter) SimulatedMarketSource.forSource(
                        id,
                        properties.simulated().seed(),
                        properties.simulated().tokenCount(),
                        clock,
                        scenario))
                .toList());
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
                (sourceId, attempt) -> Counter.builder("market_collection_retries_total")
                        .tag("source", sourceId)
                        .register(meters)
                        .increment());
    }

    @Bean
    public MarketCollector marketCollector(
            MarketStore store, SourceFetcher fetcher, TransactionTemplate transactions, Clock clock) {
        return new MarketCollector(store, fetcher, new TransactionUnitOfWork(transactions), clock);
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
