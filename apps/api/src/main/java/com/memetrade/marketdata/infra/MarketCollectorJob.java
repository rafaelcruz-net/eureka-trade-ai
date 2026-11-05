package com.memetrade.marketdata.infra;

import com.memetrade.marketdata.api.MarketCollectionCompleted;
import com.memetrade.marketdata.api.MarketSourceAdapter;
import com.memetrade.marketdata.api.SourceStatus;
import com.memetrade.marketdata.domain.CollectionOutcome;
import com.memetrade.marketdata.domain.MarketCollector;
import com.memetrade.marketdata.domain.MarketStore;
import com.memetrade.marketdata.domain.SourceState;
import com.memetrade.shared.web.CorrelationId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Collection schedule per source (F1, reliability-design.md). A cheap dispatcher runs every second: each source whose
 * interval elapsed since its last start, and that is not running here, is collected on its own thread of the scheduler
 * pool, so a slow source never delays the other. Every collection goes through the ShedLock lock
 * {@code market-collect-<sourceId>} ({@code lockAtMostFor} 2 min): an overlapping run (another instance, or a
 * collection longer than the interval) is skipped and counted as {@code SKIPPED_LOCKED}. The first dispatch happens at
 * start-up, so each source collects at once.
 *
 * <p>Metrics (observability-design.md): {@code market_source_status} (0 PENDING, 1 OK, 2 DEGRADED, 3 FAILED),
 * {@code market_collection_total}, {@code market_collection_duration_seconds}, {@code market_collection_discarded_total},
 * {@code market_source_last_success_timestamp_seconds} and the U1 gauge
 * {@code market_source_last_collection_age_seconds}. Labels only from closed lists (never a mint, chain or person).
 */
@org.springframework.stereotype.Component
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class MarketCollectorJob {

    private static final Logger log = LoggerFactory.getLogger(MarketCollectorJob.class);
    static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(2);

    private final Map<String, MarketSourceAdapter> adapters = new LinkedHashMap<>();
    private final MarketCollector collector;
    private final MarketStore store;
    private final MarketSourceRegistry registry;
    private final LockingTaskExecutor locks;
    private final Executor executor;
    private final ApplicationEventPublisher events;
    private final Duration interval;
    private final Clock clock;
    private final MeterRegistry meters;
    private final Map<String, SourceState> states = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastStart = new ConcurrentHashMap<>();
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public MarketCollectorJob(Settings settings, MeterRegistry meters) {
        settings.adapters().forEach(adapter -> adapters.put(adapter.source().sourceId(), adapter));
        this.collector = settings.collector();
        this.store = settings.store();
        this.registry = settings.registry();
        this.locks = settings.locks();
        this.executor = settings.executor();
        this.events = settings.events();
        this.interval = settings.interval();
        this.clock = settings.clock();
        this.meters = meters;
        registry.registerAll();
        refreshStates();
        adapters.keySet().forEach(this::registerGauges);
    }

    /** Everything the job needs, wired by {@code MarketDataConfig}. */
    public record Settings(
            List<MarketSourceAdapter> adapters,
            MarketCollector collector,
            MarketStore store,
            MarketSourceRegistry registry,
            LockingTaskExecutor locks,
            Executor executor,
            ApplicationEventPublisher events,
            Duration interval,
            Clock clock) {}

    /** Dispatcher: starts every due source on its own thread (at start-up all of them are due). */
    @Scheduled(fixedDelay = 1_000, initialDelay = 0)
    public void dispatch() {
        Instant now = clock.instant();
        for (String sourceId : adapters.keySet()) {
            Instant last = lastStart.get(sourceId);
            boolean due = last == null || !now.isBefore(last.plus(interval));
            if (due && running.add(sourceId)) {
                lastStart.put(sourceId, now);
                executor.execute(() -> {
                    try {
                        collectNow(sourceId);
                    } finally {
                        running.remove(sourceId);
                    }
                });
            }
        }
    }

    /**
     * One collection of {@code sourceId} through its lock, with its own correlation id; never throws.
     *
     * @return the outcome, or empty when the lock was held (skipped) or the database failed
     */
    public Optional<CollectionOutcome> collectNow(String sourceId) {
        MarketSourceAdapter adapter = adapters.get(sourceId);
        if (adapter == null) {
            throw new IllegalArgumentException("Unknown source " + sourceId);
        }
        return CorrelationId.withNew(() -> lockedCollection(adapter));
    }

    public List<String> sourceIds() {
        return List.copyOf(adapters.keySet());
    }

    private Optional<CollectionOutcome> lockedCollection(MarketSourceAdapter adapter) {
        String sourceId = adapter.source().sourceId();
        LockConfiguration lock =
                new LockConfiguration(clock.instant(), "market-collect-" + sourceId, LOCK_AT_MOST_FOR, Duration.ZERO);
        try {
            LockingTaskExecutor.TaskResult<CollectionOutcome> result =
                    locks.executeWithLock(() -> timedCollection(adapter), lock);
            if (!result.wasExecuted()) {
                counter(sourceId, "SKIPPED_LOCKED").increment();
                log.debug("Market collection skipped, another run holds the lock: source={}", sourceId);
                return Optional.empty();
            }
            CollectionOutcome outcome = result.getResult();
            events.publishEvent(new MarketCollectionCompleted(sourceId, outcome.status(), clock.instant()));
            return Optional.of(outcome);
        } catch (DataAccessException unavailable) {
            log.error(
                    "Market collection could not be stored: source={}, exceptionClass={}",
                    sourceId,
                    unavailable.getClass().getSimpleName());
            return Optional.empty();
        } catch (RuntimeException | Error unexpected) {
            throw unexpected;
        } catch (Throwable checked) {
            throw new IllegalStateException("Market collection failed with a checked exception", checked);
        }
    }

    private CollectionOutcome timedCollection(MarketSourceAdapter adapter) {
        String sourceId = adapter.source().sourceId();
        registry.ensureRegistered(adapter);
        Timer.Sample sample = Timer.start(meters);
        CollectionOutcome outcome = collector.collect(adapter);
        sample.stop(Timer.builder("market_collection_duration_seconds")
                .tag("source", sourceId)
                .register(meters));
        record(outcome);
        store.sourceState(sourceId).ifPresent(state -> states.put(sourceId, state));
        return outcome;
    }

    private void record(CollectionOutcome outcome) {
        String sourceId = outcome.sourceId();
        counter(sourceId, outcome.status().name()).increment();
        outcome.discarded().forEach((reason, count) -> Counter.builder("market_collection_discarded_total")
                .tag("source", sourceId)
                .tag("reason", reason.name())
                .register(meters)
                .increment(count));
        if (outcome.changed()) {
            log.info(
                    "Market source state changed: source={}, from={}, to={}, failureCategory={}",
                    sourceId,
                    outcome.previous(),
                    outcome.status(),
                    outcome.category().map(Enum::name).orElse("NONE"));
        }
        switch (outcome.status()) {
            case FAILED ->
                log.error(
                        "Market collection failed: source={}, failureCategory={}, exceptionClass={}",
                        sourceId,
                        outcome.category().map(Enum::name).orElse("NONE"),
                        outcome.exceptionClass().orElse("NONE"));
            case DEGRADED ->
                log.warn(
                        "Market collection degraded: source={}, retries={}, discarded={}",
                        sourceId,
                        outcome.retries(),
                        outcome.discarded());
            default -> log.info("Market source collected: source={}, tokens={}", sourceId, outcome.tokens());
        }
    }

    private Counter counter(String sourceId, String result) {
        return Counter.builder("market_collection_total")
                .tag("source", sourceId)
                .tag("result", result)
                .register(meters);
    }

    private void refreshStates() {
        try {
            for (String sourceId : adapters.keySet()) {
                store.sourceState(sourceId).ifPresent(state -> states.put(sourceId, state));
            }
        } catch (DataAccessException unavailable) {
            log.error(
                    "Market source states could not be read: {}",
                    unavailable.getClass().getSimpleName());
        }
    }

    private void registerGauges(String sourceId) {
        Gauge.builder("market_source_status", this, job -> job.statusCode(sourceId))
                .tag("source", sourceId)
                .register(meters);
        Gauge.builder("market_source_last_success_timestamp_seconds", this, job -> job.lastSuccess(sourceId)
                        .map(Instant::getEpochSecond)
                        .orElse(0L))
                .tag("source", sourceId)
                .register(meters);
        Gauge.builder("market_source_last_collection_age_seconds", this, job -> job.lastSuccess(sourceId)
                        .map(at -> Duration.between(at, job.clock.instant()).toSeconds())
                        .orElse(-1L))
                .tag("source", sourceId)
                .register(meters);
    }

    /** Cached state of the last collection (or of the start-up), for the gauges; never a database call. */
    public Optional<SourceState> cachedState(String sourceId) {
        return Optional.ofNullable(states.get(sourceId));
    }

    Optional<Instant> lastSuccess(String sourceId) {
        return cachedState(sourceId).flatMap(SourceState::lastUpdatedAt);
    }

    int statusCode(String sourceId) {
        return cachedState(sourceId)
                .map(SourceState::status)
                .orElse(SourceStatus.State.PENDING)
                .ordinal();
    }
}
