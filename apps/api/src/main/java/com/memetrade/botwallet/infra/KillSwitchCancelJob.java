package com.memetrade.botwallet.infra;

import com.memetrade.botwallet.application.RealKillSwitchService;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.web.CorrelationId;
import java.time.Duration;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 261113-trading-real-bot U5 (BR3.1, U5-NFR3.5, U5-NFR4.2): the cancellation job of the kill switch REAL, every minute,
 * once across instances. It always runs (the heartbeat {@code job="kill-switch-cancel"}) and acts only while the switch
 * is on, until no unsent order is left (convergence within 2 minutes).
 */
@Component
@ProcessRole(ProcessRole.Kind.WORKER)
@ConditionalOnProperty(name = "memetrade.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class KillSwitchCancelJob {

    private final RealKillSwitchService killSwitch;
    private final RealOpsMetrics metrics;

    public KillSwitchCancelJob(RealKillSwitchService killSwitch, RealOpsMetrics metrics) {
        this.killSwitch = killSwitch;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${memetrade.real.ops.kill-switch-cancel-interval}")
    @SchedulerLock(name = "realOpsKillSwitchCancel", lockAtMostFor = "55s", lockAtLeastFor = "5s")
    public void run() {
        CorrelationId.runWithNew(() -> {
            long started = System.nanoTime();
            killSwitch.cancelUnsentWhileActive();
            metrics.jobRan("kill-switch-cancel", Duration.ofNanos(System.nanoTime() - started));
        });
    }
}
