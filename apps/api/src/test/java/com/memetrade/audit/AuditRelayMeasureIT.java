package com.memetrade.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.audit.api.AuditActions;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.audit.infra.AuditDeliveryMetrics;
import com.memetrade.audit.infra.AuditOutboxRelay;
import com.memetrade.testsupport.AbstractPostgresIT;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** U2 relay (F2): recorded_at from the clock, the real commit-to-record delivery sample and the daily count. */
@Tag("u2")
class AuditRelayMeasureIT extends AbstractPostgresIT {

    @Autowired
    private AuditEventPublisher publisher;

    @Autowired
    private AuditOutboxRelay relay;

    @Autowired
    private AuditDeliveryMetrics metrics;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void drainEarlierEvents() {
        while (relay.relayBatch() > 0) {
            // other test classes may have left committed rows pending; this test measures only its own
        }
    }

    @Test
    void theRelayWritesRecordedAtFromTheInjectableClock() {
        AuditEventCommand event = publish();
        clock.advance(Duration.ofSeconds(3));

        relay.relayBatch();

        Timestamp recorded = jdbc.sql("select recorded_at from audit_event where event_id = :id")
                .param("id", event.eventId())
                .query(Timestamp.class)
                .single();
        assertThat(recorded.toInstant()).isEqualTo(clock.instant());
    }

    @Test
    void eachDeliveryIsMeasuredFromTheCommitOfTheAction() {
        assertThat(metrics.commitTimestampsTracked()).isTrue();
        Timer delivery = registry.get("audit_delivery").timer();
        long before = delivery.count();
        double totalBefore = delivery.totalTime(TimeUnit.MILLISECONDS);
        AuditEventCommand event = publish();
        Instant committed = jdbc.sql("select pg_xact_commit_timestamp(xmin) from audit_outbox where event_id = :id")
                .param("id", event.eventId())
                .query(Timestamp.class)
                .single()
                .toInstant();
        clock.set(committed.plusMillis(1500));

        relay.relayBatch();

        assertThat(delivery.count()).isEqualTo(before + 1);
        assertThat(delivery.totalTime(TimeUnit.MILLISECONDS) - totalBefore).isEqualTo(1500.0);
    }

    @Test
    void theDailyCountGrowsByTheEventsActuallyRecorded() {
        long before = metrics.recordedToday();
        AuditEventCommand first = publish();
        publish();
        tx.executeWithoutResult(status -> publisher.publish(first));

        relay.relayBatch();

        assertThat(metrics.recordedToday()).isEqualTo(before + 2);
        assertThat(registry.get("audit_events_recorded_today").gauge().value()).isEqualTo(before + 2.0);
    }

    @Test
    void theStartupCountReadsTheEventsRecordedTodayFromTheTrail() {
        publish();
        relay.relayBatch();
        Instant dayStart = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        long expected = jdbc.sql("select count(*) from audit_event where recorded_at >= :from and recorded_at < :to")
                .param("from", Timestamp.from(dayStart))
                .param("to", Timestamp.from(dayStart.plus(Duration.ofDays(1))))
                .query(Long.class)
                .single();

        metrics.initializeTodayCount();

        assertThat(metrics.recordedToday()).isEqualTo(expected).isPositive();
    }

    private AuditEventCommand publish() {
        AuditEventCommand event = AuditEventCommand.anonymous(
                AuditActions.LOGIN_REJECTED,
                "AbCd…WxYz",
                null,
                UUID.randomUUID().toString(),
                clock.instant(),
                Map.of("reason", "MEASURE"));
        tx.executeWithoutResult(status -> publisher.publish(event));
        return event;
    }
}
