package com.memetrade.botwallet.infra;

import com.memetrade.botwallet.application.RestoreService;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.web.CorrelationId;
import java.time.Duration;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 261113-trading-real-bot U5 (F10, BR2.5, U5-NFR4.4, U5-NFR5.13): the restore check of the worker. When the worker is
 * ready it opens the marker RESTORE_CHECK_PENDING before any read, then checks; while the check stays pending (the RPC
 * did not answer) the job {@code restore-check} tries again every minute. The job always runs and counts its heartbeat;
 * it acts only while the check is pending (its stopped-job alert fires only then).
 */
@Component
@ProcessRole(ProcessRole.Kind.WORKER)
@ConditionalOnProperty(name = "memetrade.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class RestoreDetector {

    private final RestoreService restore;
    private final RealOpsMetrics metrics;

    public RestoreDetector(RestoreService restore, RealOpsMetrics metrics) {
        this.restore = restore;
        this.metrics = metrics;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void onReady() {
        CorrelationId.runWithNew(() -> {
            restore.openCheck();
            restore.check();
        });
    }

    @Scheduled(fixedDelayString = "${memetrade.real.ops.restore-check-interval}")
    @SchedulerLock(name = "realOpsRestoreCheck", lockAtMostFor = "55s", lockAtLeastFor = "5s")
    public void run() {
        CorrelationId.runWithNew(() -> {
            long started = System.nanoTime();
            restore.check();
            metrics.jobRan("restore-check", Duration.ofNanos(System.nanoTime() - started));
        });
    }
}
