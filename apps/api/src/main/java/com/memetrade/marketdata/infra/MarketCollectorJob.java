package com.memetrade.marketdata.infra;

import com.memetrade.marketdata.api.SourceStatus;
import com.memetrade.marketdata.domain.MarketCollector;
import com.memetrade.marketdata.domain.MarketStore;
import com.memetrade.shared.web.CorrelationId;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Collects the simulated source every 15 seconds (performance-design.md Q4) and publishes
 * {@code market_source_status} (0 OK, 1 FAILED) and {@code market_source_last_collection_age_seconds}.
 */
@Component
public class MarketCollectorJob {

    private static final Logger log = LoggerFactory.getLogger(MarketCollectorJob.class);

    private final MarketCollector collector;
    private final MarketStore store;
    private final Clock clock;

    public MarketCollectorJob(MarketCollector collector, MarketStore store, Clock clock, MeterRegistry registry) {
        this.collector = collector;
        this.store = store;
        this.clock = clock;
        String sourceId = collector.adapter().source().sourceId();
        Gauge.builder("market_source_status", this, job -> job.statusValue())
                .tag("source", sourceId)
                .register(registry);
        Gauge.builder("market_source_last_collection_age_seconds", this, job -> job.collectionAge()
                        .map(Duration::toSeconds)
                        .orElse(-1L))
                .tag("source", sourceId)
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${memetrade.market.collect-interval}", initialDelayString = "PT1S")
    public void run() {
        CorrelationId.runWithNew(this::cycle);
    }

    void cycle() {
        try {
            collector.collect();
        } catch (DataAccessException failure) {
            log.error(
                    "Market collection could not be stored: {}",
                    failure.getClass().getSimpleName());
        }
    }

    /** Age of the last successful collection, empty when the source never collected. */
    public Optional<Duration> collectionAge() {
        return status().flatMap(SourceStatus::lastUpdatedAt).map(last -> Duration.between(last, clock.instant()));
    }

    public Optional<SourceStatus> status() {
        try {
            return store.sourceStatus(collector.adapter().source().sourceId());
        } catch (DataAccessException unavailable) {
            return Optional.empty();
        }
    }

    private double statusValue() {
        return status().map(s -> s.status() == SourceStatus.State.OK ? 0.0 : 1.0)
                .orElse(1.0);
    }
}
