package com.memetrade.audit.infra;

import com.memetrade.audit.domain.AuditJob;
import com.memetrade.audit.domain.AuditJobPolicy;
import com.memetrade.audit.domain.AuditJobRun;
import com.memetrade.audit.domain.AuditJobRunStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Startup recovery (NFR4.5): when the API is ready, each daily job without a row, without a success, or whose last
 * success is older than 24 h runs at once, always through ShedLock. Covers a machine switched off at night and a new
 * database (CI, tests), where the health stays DEGRADED until the first success.
 */
@Component
public class AuditJobRecovery {

    private static final Logger log = LoggerFactory.getLogger(AuditJobRecovery.class);

    private final AuditJobRunStore runs;
    private final AuditJobRunner runner;
    private final AuditJobMetrics metrics;
    private final AuditJobPolicy policy;
    private final Clock clock;

    public AuditJobRecovery(
            AuditJobRunStore runs,
            AuditJobRunner runner,
            AuditJobMetrics metrics,
            AuditProperties properties,
            Clock clock) {
        this.runs = runs;
        this.runner = runner;
        this.metrics = metrics;
        this.policy = new AuditJobPolicy(
                properties.jobs().recoverAfter(), properties.jobs().degradedAfter());
        this.clock = clock;
    }

    /**
     * Seeds the last-success gauges and runs the overdue jobs.
     *
     * @return the jobs that were overdue and attempted
     */
    public List<AuditJob> recoverOverdueJobs() {
        List<AuditJob> attempted = new ArrayList<>();
        for (AuditJob job : AuditJob.values()) {
            try {
                Optional<AuditJobRun> run = runs.find(job);
                run.flatMap(AuditJobRun::lastSuccessAt).ifPresent(success -> metrics.lastSuccessAt(job, success));
                if (policy.shouldRunAtStartup(run, clock.instant())) {
                    log.warn("Overdue audit job runs at startup: job={}, hoursSinceLastSuccess={}", job, hours(run));
                    attempted.add(job);
                    runner.runLocked(job);
                }
            } catch (DataAccessException unavailable) {
                log.error(
                        "Audit job recovery could not run: job={}, cause={}",
                        job,
                        AuditDailyJob.technicalCause(unavailable));
            }
        }
        return attempted;
    }

    private String hours(Optional<AuditJobRun> run) {
        Instant now = clock.instant();
        return AuditJobPolicy.sinceLastSuccess(run, now)
                .map(Duration::toHours)
                .map(String::valueOf)
                .orElse("never");
    }
}
