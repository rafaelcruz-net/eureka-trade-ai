package com.memetrade.privacy.infra;

import com.memetrade.privacy.domain.request.DataRequestOrigin;
import com.memetrade.privacy.domain.request.DataRequestType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Metrics of the data subject requests (observability-design.md NFR8.1; monitoring-design.md). The labels are closed
 * values ({@code type}, {@code origin}); never a tenant, a person, a request, the text or the contact.
 *
 * <ul>
 *   <li>{@code memetrade_data_requests_created_total{type,origin}};
 *   <li>{@code memetrade_data_requests_open{type}}, {@code memetrade_data_requests_overdue} and
 *       {@code memetrade_data_requests_due_soon}: gauges that read ONLY the cached counts, refreshed every 60 s by
 *       {@link DataRequestCountsRefresher} in each instance. Collecting never reads the database and never throws; the
 *       health {@code dataRequests} reads the same cache.
 * </ul>
 */
@Component
public class DataRequestMetrics {

    private final MeterRegistry registry;
    private final Map<DataRequestType, AtomicLong> open = new EnumMap<>(DataRequestType.class);
    private final AtomicLong overdue = new AtomicLong();
    private final AtomicLong dueSoon = new AtomicLong();
    private final AtomicReference<Instant> refreshedAt = new AtomicReference<>();

    public DataRequestMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (DataRequestType type : DataRequestType.values()) {
            AtomicLong count = new AtomicLong();
            open.put(type, count);
            Gauge.builder("memetrade.data.requests.open", count, AtomicLong::get)
                    .description("Open data subject requests by type (cached, refreshed every 60 s)")
                    .tag("type", type.name())
                    .register(registry);
        }
        Gauge.builder("memetrade.data.requests.overdue", overdue, AtomicLong::get)
                .description("Open data subject requests past their due date (cached, refreshed every 60 s)")
                .register(registry);
        Gauge.builder("memetrade.data.requests.due.soon", dueSoon, AtomicLong::get)
                .description("Open data subject requests due within 3 days and not overdue yet (cached)")
                .register(registry);
        for (DataRequestType type : DataRequestType.values()) {
            for (DataRequestOrigin origin : DataRequestOrigin.values()) {
                created(type, origin);
            }
        }
    }

    /** One created request (ACCOUNT or EMAIL). */
    public void requestCreated(DataRequestType type, DataRequestOrigin origin) {
        created(type, origin).increment();
    }

    /** The counts of one refresh (NFR8.1). */
    public void refreshed(Counts counts, Instant at) {
        for (DataRequestType type : DataRequestType.values()) {
            open.get(type).set(counts.open().getOrDefault(type, 0L));
        }
        overdue.set(counts.overdue());
        dueSoon.set(counts.dueSoon());
        refreshedAt.set(at);
    }

    /** The cached number of overdue open requests, read by the health. */
    public long overdue() {
        return overdue.get();
    }

    public long dueSoon() {
        return dueSoon.get();
    }

    public long open(DataRequestType type) {
        return open.get(type).get();
    }

    /** When the cache was last refreshed; empty before the first refresh. */
    public Optional<Instant> refreshedAt() {
        return Optional.ofNullable(refreshedAt.get());
    }

    private Counter created(DataRequestType type, DataRequestOrigin origin) {
        return Counter.builder("memetrade.data.requests.created")
                .description("Data subject requests created, by type and origin")
                .tag("type", type.name())
                .tag("origin", origin.name())
                .register(registry);
    }

    /** One aggregate read of the counts. */
    public record Counts(Map<DataRequestType, Long> open, long overdue, long dueSoon) {
        public Counts {
            open = Map.copyOf(open);
        }
    }
}
