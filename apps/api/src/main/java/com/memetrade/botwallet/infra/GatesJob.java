package com.memetrade.botwallet.infra;

import com.memetrade.botwallet.application.GatesService;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.web.CorrelationId;
import java.time.Duration;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 261113-trading-real-bot U5 (BR1.4, U5-NFR4.2, U5-NFR5.6): the gates job of the worker, every 5 minutes, once across
 * instances (ShedLock {@code realOpsGates}). It treats the releases whose quarantine passed and checks the BLOCKED ones
 * again in the same run; every run that ends well counts the heartbeat {@code job="gates"}.
 */
@Component
@ProcessRole(ProcessRole.Kind.WORKER)
@ConditionalOnProperty(name = "memetrade.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class GatesJob {

    private final GatesService gates;
    private final RealOpsMetrics metrics;

    public GatesJob(GatesService gates, RealOpsMetrics metrics) {
        this.gates = gates;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${memetrade.real.ops.gates-interval}")
    @SchedulerLock(name = "realOpsGates", lockAtMostFor = "4m", lockAtLeastFor = "30s")
    public void run() {
        CorrelationId.runWithNew(() -> {
            long started = System.nanoTime();
            gates.run();
            metrics.jobRan("gates", Duration.ofNanos(System.nanoTime() - started));
        });
    }
}
