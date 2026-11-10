package com.memetrade.identity.infra;

import com.memetrade.identity.domain.purge.IdentityPurge;
import com.memetrade.identity.domain.purge.PurgeHealthPolicy;
import com.memetrade.identity.domain.purge.PurgeRun;
import com.memetrade.identity.domain.purge.PurgeRunStore;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Startup recovery of the Identity purges (BR2.16, NFR7.3), like {@code AuditJobRecovery}: each purge without a row,
 * without a success, or whose last success is older than {@code recover-after} (24 hours) runs at once, always through
 * the same ShedLock lock as the schedule. Also seeds the cached last-success gauge. A database failure is logged and
 * the start goes on; the purge runs at its scheduled time.
 */
@Component
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class IdentityJobRecovery {

    private static final Logger log = LoggerFactory.getLogger(IdentityJobRecovery.class);

    private final PurgeRunStore runs;
    private final IdentityJobRunner runner;
    private final IdentityJobMetrics metrics;
    private final PurgeHealthPolicy policy;
    private final Clock clock;

    public IdentityJobRecovery(
            PurgeRunStore runs,
            IdentityJobRunner runner,
            IdentityJobMetrics metrics,
            IdentityPurgeProperties settings,
            Clock clock) {
        this.runs = runs;
        this.runner = runner;
        this.metrics = metrics;
        this.policy = new PurgeHealthPolicy(settings.degradedAfter(), settings.recoverAfter());
        this.clock = clock;
    }

    /**
     * Seeds the gauges and runs the overdue purges.
     *
     * @return the purges that were overdue and attempted
     */
    public List<IdentityPurge> recoverOverduePurges() {
        List<IdentityPurge> attempted = new ArrayList<>();
        for (IdentityPurge purge : IdentityPurge.values()) {
            try {
                Optional<PurgeRun> run = runs.find(purge);
                run.flatMap(PurgeRun::lastSuccessAt).ifPresent(success -> metrics.lastSuccessAt(purge, success));
                if (policy.shouldRunAtStartup(run, clock.instant())) {
                    log.warn(
                            "Overdue purge runs at startup: purge={}, hoursSinceLastSuccess={}",
                            purge.purgeId(),
                            hours(run));
                    attempted.add(purge);
                    runner.runLocked(purge);
                }
            } catch (DataAccessException unavailable) {
                log.error(
                        "Purge recovery could not run: purge={}, cause={}",
                        purge.purgeId(),
                        IdentityJobRunner.technicalCause(unavailable));
            }
        }
        return attempted;
    }

    private String hours(Optional<PurgeRun> run) {
        return PurgeHealthPolicy.sinceLastSuccess(run, clock.instant())
                .map(Duration::toHours)
                .map(String::valueOf)
                .orElse("never");
    }
}
