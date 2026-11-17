package com.memetrade.realexits.infra;

import com.memetrade.realexits.domain.CancellationWatch;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * U7-NFR5.1, U7-NFR5.3, U7-NFR5.6b (observability-design.md): every 30 s in the worker, the gauges read from the
 * database: the conditionals by state, the age of the oldest CANCELLING (alert at 900 s, U7-NFR5.7) and the age of the
 * oldest successful read among the OPEN ones (alert at 7.200 s, U7-NFR5.8). Only counts and ages, never a person.
 */
@Component
public class RealExitsGaugesJob {

    private final JdbcConditionalStore store;
    private final RealExitsMetrics metrics;
    private final Clock clock;

    public RealExitsGaugesJob(JdbcConditionalStore store, RealExitsMetrics metrics, Clock clock) {
        this.store = store;
        this.metrics = metrics;
        this.clock = clock;
    }

    public void runOnce() {
        Instant now = clock.instant();
        long cancellingOldest = store.oldestCancellingSince()
                .map(since -> CancellationWatch.secondsSince(since, now))
                .orElse(0L);
        long unreadOldest = store.oldestOpenRead()
                .map(read -> CancellationWatch.secondsSince(read, now))
                .orElse(0L);
        metrics.gauges(store.countsByState(), cancellingOldest, unreadOldest);
        metrics.jobRan("exits-gauges");
    }
}
