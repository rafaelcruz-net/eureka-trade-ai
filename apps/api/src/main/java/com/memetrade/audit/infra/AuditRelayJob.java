package com.memetrade.audit.infra;

import com.memetrade.shared.web.CorrelationId;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the relay every second in the API process and publishes {@code audit_outbox_pending} and
 * {@code audit_outbox_oldest_age_seconds}. A database error ends the cycle with an ERROR log; the next cycle retries.
 */
@Component
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class AuditRelayJob {

    private static final Logger log = LoggerFactory.getLogger(AuditRelayJob.class);

    private final AuditOutboxRelay relay;
    private final AtomicReference<AuditOutboxRelay.OutboxLag> lastLag =
            new AtomicReference<>(new AuditOutboxRelay.OutboxLag(0, Duration.ZERO));

    public AuditRelayJob(AuditOutboxRelay relay, MeterRegistry registry) {
        this.relay = relay;
        Gauge.builder("audit_outbox_pending", lastLag, ref -> ref.get().pending())
                .description("Audit events waiting for the relay")
                .register(registry);
        Gauge.builder("audit_outbox_oldest_age_seconds", lastLag, ref -> ref.get()
                        .oldestAge()
                        .toSeconds())
                .description("Age of the oldest pending audit event")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${memetrade.audit.relay-interval}")
    public void run() {
        CorrelationId.runWithNew(this::cycle);
    }

    /** One batch (up to 100 rows) per cycle, then the lag is measured for metrics and health. */
    void cycle() {
        try {
            relay.relayBatch();
            lastLag.set(relay.lag());
        } catch (DataAccessException failure) {
            log.error(
                    "Audit relay cycle failed: {}; next cycle retries",
                    failure.getClass().getSimpleName());
        }
    }

    /** Last measured lag (updated every cycle). */
    public AuditOutboxRelay.OutboxLag lastLag() {
        return lastLag.get();
    }
}
