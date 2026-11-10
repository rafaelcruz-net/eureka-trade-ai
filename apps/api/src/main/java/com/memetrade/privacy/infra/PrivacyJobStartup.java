package com.memetrade.privacy.infra;

import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.web.CorrelationId;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * When the api is ready, hands the startup recovery of the Privacy purges ({@link PrivacyJobRecovery}) to the
 * scheduler pool, so a long purge never delays readiness (NFR7.1). Only where the schedulers run; integration tests
 * drive the recovery directly.
 */
@Component
@ConditionalOnProperty(name = "memetrade.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@ProcessRole(ProcessRole.Kind.API)
public class PrivacyJobStartup {

    private final PrivacyJobRecovery recovery;
    private final TaskScheduler scheduler;
    private final Clock clock;

    public PrivacyJobStartup(PrivacyJobRecovery recovery, TaskScheduler scheduler, Clock clock) {
        this.recovery = recovery;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        scheduler.schedule(() -> CorrelationId.runWithNew(recovery::recoverOverduePurges), clock.instant());
    }
}
