package com.memetrade.administration.infra;

import com.memetrade.shared.config.ProcessRole;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Q2 of the NFR Design: one reconciliation round as soon as the api is ready, so a crash between the commit of the
 * state and the call of U5 is repaired at start-up instead of 30 s later. Handed to the scheduler pool, so readiness
 * never waits for it; only where the schedulers run (integration tests drive the reconciliation directly).
 */
@Component
@ConditionalOnProperty(name = "memetrade.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@ProcessRole(ProcessRole.Kind.API)
public class KillSwitchStartup {

    private final KillSwitchReconciler reconciler;
    private final TaskScheduler scheduler;
    private final Clock clock;

    public KillSwitchStartup(KillSwitchReconciler reconciler, TaskScheduler scheduler, Clock clock) {
        this.reconciler = reconciler;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        scheduler.schedule(reconciler::runOnce, clock.instant());
    }
}
