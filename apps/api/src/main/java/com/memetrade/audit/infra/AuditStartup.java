package com.memetrade.audit.infra;

import com.memetrade.shared.web.CorrelationId;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Work of the AuditTrail when the API is ready (only where the schedulers run; integration tests drive it directly):
 *
 * <ul>
 *   <li>checks {@code track_commit_timestamp} (ERROR when off) and seeds {@code audit_events_recorded_today} from
 *       {@code recorded_at};
 *   <li>hands the startup recovery of the daily jobs ({@link AuditJobRecovery}) to the scheduler pool, so a long
 *       purge never delays readiness.
 * </ul>
 *
 * A database error here never stops the API; the relay measures again on its next cycle and the jobs run at their
 * scheduled time.
 */
@Component
@ConditionalOnProperty(name = "memetrade.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class AuditStartup {

    private static final Logger log = LoggerFactory.getLogger(AuditStartup.class);

    private final AuditDeliveryMetrics metrics;
    private final AuditJobRecovery recovery;
    private final TaskScheduler scheduler;
    private final Clock clock;

    public AuditStartup(AuditDeliveryMetrics metrics, AuditJobRecovery recovery, TaskScheduler scheduler, Clock clock) {
        this.metrics = metrics;
        this.recovery = recovery;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        CorrelationId.runWithNew(this::prepareMeasures);
        scheduler.schedule(() -> CorrelationId.runWithNew(recovery::recoverOverdueJobs), clock.instant());
    }

    void prepareMeasures() {
        metrics.commitTimestampsTracked();
        try {
            metrics.initializeTodayCount();
        } catch (DataAccessException unavailable) {
            log.warn(
                    "Could not seed audit_events_recorded_today ({}); counting starts from zero",
                    unavailable.getClass().getSimpleName());
        }
    }
}
