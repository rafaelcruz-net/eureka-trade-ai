package com.memetrade.privacy.infra;

import com.memetrade.privacy.domain.purge.PrivacyPurge;
import com.memetrade.privacy.domain.purge.PurgeHealthPolicy;
import com.memetrade.privacy.domain.purge.PurgeRun;
import com.memetrade.privacy.domain.purge.PurgeRunStore;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/**
 * Startup recovery of the Privacy purges (NFR7.1), like {@code IdentityJobRecovery}: a purge without a row, without a
 * success, or whose last success is older than {@code recover-after} (24 hours) runs at once, always through the same
 * ShedLock lock as the schedule. Also seeds the cached last-success gauge. A database failure is logged and the start
 * goes on; the purge runs at its scheduled time.
 */
public class PrivacyJobRecovery {

    private static final Logger log = LoggerFactory.getLogger(PrivacyJobRecovery.class);

    private final PurgeRunStore runs;
    private final PrivacyJobRunner runner;
    private final PrivacyJobMetrics metrics;
    private final PurgeHealthPolicy policy;
    private final Clock clock;

    public PrivacyJobRecovery(
            PurgeRunStore runs,
            PrivacyJobRunner runner,
            PrivacyJobMetrics metrics,
            PrivacyProperties settings,
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
    public List<PrivacyPurge> recoverOverduePurges() {
        List<PrivacyPurge> attempted = new ArrayList<>();
        for (PrivacyPurge purge : PrivacyPurge.values()) {
            try {
                Optional<PurgeRun> run = runs.find(purge);
                run.flatMap(PurgeRun::lastSuccessAt).ifPresent(success -> metrics.lastSuccessAt(purge, success));
                if (policy.shouldRunAtStartup(run, clock.instant())) {
                    log.warn(
                            "Overdue purge runs at startup: purge={}, hoursSinceLastSuccess={}",
                            purge.purgeId(),
                            PurgeHealthPolicy.sinceLastSuccess(run, clock.instant())
                                    .map(Duration::toHours)
                                    .map(String::valueOf)
                                    .orElse("never"));
                    attempted.add(purge);
                    runner.runLocked(purge);
                }
            } catch (DataAccessException unavailable) {
                log.error(
                        "Purge recovery could not run: purge={}, cause={}",
                        purge.purgeId(),
                        PrivacyJobRunner.technicalCause(unavailable));
            }
        }
        return attempted;
    }
}
