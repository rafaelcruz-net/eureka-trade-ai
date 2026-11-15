package com.memetrade.marketdata.infra.coingecko;

import com.memetrade.marketdata.domain.CollectionOutcome;
import com.memetrade.marketdata.domain.MarketStore;
import com.memetrade.marketdata.infra.MarketCollectorJob;
import com.memetrade.marketdata.infra.MarketDataProperties;
import com.memetrade.marketdata.infra.MarketSourceRegistry;
import com.memetrade.shared.config.ProcessRole;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Optional;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * U2 (infrastructure-specification.md "Deployment"): the collection schedule of the real source {@code coingecko},
 * in the WORKER only (NFR1.6). It reuses the schedule of every source ({@link MarketCollectorJob}: dispatcher every
 * second, interval {@code MARKET_COLLECTION_INTERVAL}, ShedLock lock {@code market-collect-coingecko}, metrics and
 * the event after each collection) with the CoinGecko adapter only. Created by {@link CoinGeckoConfig} when
 * {@code MARKET_SOURCES} names coingecko.
 */
@ProcessRole(ProcessRole.Kind.WORKER)
public class CoinGeckoCollectorJob {

    private final MarketCollectorJob schedule;

    public CoinGeckoCollectorJob(
            MarketCollectorJob.Settings base,
            CoinGeckoAdapter adapter,
            MarketStore store,
            MarketDataProperties properties,
            MeterRegistry meters) {
        this.schedule = new MarketCollectorJob(
                new MarketCollectorJob.Settings(
                        List.of(adapter),
                        base.collector(),
                        base.store(),
                        new MarketSourceRegistry(List.of(adapter), store, properties),
                        base.locks(),
                        base.executor(),
                        base.events(),
                        base.interval(),
                        base.clock()),
                meters);
    }

    @Scheduled(fixedDelay = 1_000, initialDelay = 0)
    public void dispatch() {
        schedule.dispatch();
    }

    /** One collection now, through the lock (tests and operations). */
    public Optional<CollectionOutcome> collectNow() {
        return schedule.collectNow(MarketDataProperties.COINGECKO);
    }
}
