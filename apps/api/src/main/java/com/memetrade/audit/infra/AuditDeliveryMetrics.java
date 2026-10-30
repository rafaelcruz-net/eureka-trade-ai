package com.memetrade.audit.infra;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Delivery measures of the relay (NFR4.2, NFR9.2, NFR9.5):
 *
 * <ul>
 *   <li>{@code audit_delivery_seconds}: histogram (0.5, 1, 2, 5, 10, 30, 60 s) from the COMMIT of the audited action
 *       ({@code pg_xact_commit_timestamp(xmin)}) to the moment the relay recorded the event;
 *   <li>{@code audit_delivery_unmeasured_total}: deliveries without a usable commit instant (row committed before the
 *       parameter was switched on, or clocks out of order), kept out of the histogram;
 *   <li>{@code audit_events_recorded_today}: events recorded in the current UTC day, an in-memory counter reset at
 *       the UTC day change and seeded once at startup from {@code recorded_at}.
 * </ul>
 *
 * When {@code track_commit_timestamp} is off, an ERROR names the parameter and no delivery sample is published; the
 * rest of the trail keeps working.
 */
@Component
public class AuditDeliveryMetrics {

    private static final Logger log = LoggerFactory.getLogger(AuditDeliveryMetrics.class);

    private final JdbcTrailStatistics statistics;
    private final Clock clock;
    private final Timer delivery;
    private final Counter unmeasured;
    private final Object todayLock = new Object();
    private volatile Boolean commitTimestampsTracked;
    private LocalDate today;
    private long recordedToday;

    public AuditDeliveryMetrics(JdbcTrailStatistics statistics, Clock clock, MeterRegistry registry) {
        this.statistics = statistics;
        this.clock = clock;
        this.today = utcDay(clock.instant());
        this.delivery = Timer.builder("audit_delivery")
                .description("From the commit of the audited action to the event recorded in the trail")
                .serviceLevelObjectives(
                        Duration.ofMillis(500),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(60))
                .register(registry);
        this.unmeasured = Counter.builder("audit_delivery_unmeasured")
                .description("Deliveries without a commit instant, kept out of audit_delivery_seconds")
                .register(registry);
        Gauge.builder("audit_events_recorded_today", this, AuditDeliveryMetrics::recordedToday)
                .description("Events recorded in the current UTC day (ceiling: 100 thousand)")
                .register(registry);
    }

    /**
     * Whether {@code track_commit_timestamp} is on. Read once; when it is off an ERROR is logged once. A database
     * error is not cached, so the next relay cycle asks again.
     */
    public boolean commitTimestampsTracked() {
        Boolean known = commitTimestampsTracked;
        if (known != null) {
            return known;
        }
        try {
            boolean tracked = "on".equalsIgnoreCase(statistics.trackCommitTimestamp());
            if (!tracked) {
                log.error("PostgreSQL parameter track_commit_timestamp is off: audit_delivery_seconds is not"
                        + " published until the database runs with track_commit_timestamp=on");
            }
            commitTimestampsTracked = tracked;
            return tracked;
        } catch (DataAccessException unavailable) {
            log.warn(
                    "Could not read track_commit_timestamp ({}); the delivery is not measured in this cycle",
                    unavailable.getClass().getSimpleName());
            return false;
        }
    }

    /** One delivered event: a histogram sample when the commit instant is known and not after the record. */
    public void delivered(Optional<Instant> committedAt, Instant recordedAt) {
        if (committedAt.isEmpty() || committedAt.get().isAfter(recordedAt)) {
            unmeasured.increment();
            return;
        }
        delivery.record(Duration.between(committedAt.get(), recordedAt));
    }

    /** Adds events recorded by the relay to the count of the current UTC day. */
    public void recorded(long events) {
        synchronized (todayLock) {
            rollDay();
            recordedToday += events;
        }
    }

    /** Events recorded in the current UTC day; zero right after the UTC day changes. */
    public long recordedToday() {
        synchronized (todayLock) {
            rollDay();
            return recordedToday;
        }
    }

    /** Seeds the daily count from {@code recorded_at} (startup, once). */
    public void initializeTodayCount() {
        Instant start = utcDay(clock.instant()).atStartOfDay(ZoneOffset.UTC).toInstant();
        long count = statistics.countRecordedBetween(start, start.plus(Duration.ofDays(1)));
        synchronized (todayLock) {
            today = utcDay(start);
            recordedToday = count;
        }
    }

    private void rollDay() {
        LocalDate now = utcDay(clock.instant());
        if (!now.equals(today)) {
            today = now;
            recordedToday = 0;
        }
    }

    private static LocalDate utcDay(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate();
    }
}
