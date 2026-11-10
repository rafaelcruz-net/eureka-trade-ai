package com.memetrade.identity.infra;

import com.memetrade.shared.web.CorrelationId;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * When the api is ready, hands the startup recovery of the Identity purges ({@link IdentityJobRecovery}) to the
 * scheduler pool, so a long purge never delays readiness (BR2.16). Only where the schedulers run; integration tests
 * drive the recovery directly.
 */
@Component
@ConditionalOnProperty(name = "memetrade.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class IdentityJobStartup {

    private final IdentityJobRecovery recovery;
    private final TaskScheduler scheduler;
    private final Clock clock;

    public IdentityJobStartup(IdentityJobRecovery recovery, TaskScheduler scheduler, Clock clock) {
        this.recovery = recovery;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        scheduler.schedule(() -> CorrelationId.runWithNew(recovery::recoverOverduePurges), clock.instant());
    }
}
