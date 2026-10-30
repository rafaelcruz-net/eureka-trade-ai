package com.memetrade.audit.infra;

import com.memetrade.audit.domain.AuditJob;
import com.memetrade.shared.web.CorrelationId;
import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockingTaskExecutor.TaskResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Single entry point of the daily jobs, for the schedule and for the startup recovery (NFR4.5, NFR4.6): every run goes
 * through ShedLock ({@code lockAtMostFor} 35 min, {@code lockAtLeastFor} 5 min, database time), so two schedulers or
 * a recovery next to the scheduled time give a single run. A caller that does not get the lock returns at once, with
 * nothing deleted and no error (DEBUG log). Each run gets its own {@code correlationId}.
 */
@Component
public class AuditJobRunner {

    private static final Logger log = LoggerFactory.getLogger(AuditJobRunner.class);

    private final LockingTaskExecutor locks;
    private final Map<AuditJob, AuditDailyJob> jobs = new EnumMap<>(AuditJob.class);
    private final AuditProperties.Jobs settings;
    private final Clock clock;

    public AuditJobRunner(
            LockingTaskExecutor locks, List<AuditDailyJob> dailyJobs, AuditProperties properties, Clock clock) {
        this.locks = locks;
        dailyJobs.forEach(job -> jobs.put(job.job(), job));
        if (!jobs.keySet().containsAll(List.of(AuditJob.values()))) {
            throw new IllegalStateException("Every AuditJob needs its AuditDailyJob bean; found " + jobs.keySet());
        }
        this.settings = properties.jobs();
        this.clock = clock;
    }

    /**
     * Runs {@code job} when its lock is free.
     *
     * @return the result of the run, or empty when another run holds the lock or the lock could not be read
     */
    public Optional<AuditDailyJob.RunResult> runLocked(AuditJob job) {
        LockConfiguration lock =
                new LockConfiguration(clock.instant(), job.name(), settings.lockAtMostFor(), settings.lockAtLeastFor());
        try {
            TaskResult<AuditDailyJob.RunResult> result = locks.executeWithLock(
                    () -> CorrelationId.withNew(() -> jobs.get(job).run()), lock);
            if (!result.wasExecuted()) {
                log.debug("Audit job skipped, another run holds the lock: job={}", job);
                return Optional.empty();
            }
            return Optional.of(result.getResult());
        } catch (RuntimeException | Error unexpected) {
            throw unexpected;
        } catch (Throwable checked) {
            throw new IllegalStateException("Audit job " + job + " failed with a checked exception", checked);
        }
    }

    /** Same as {@link #runLocked(AuditJob)} for the schedule: a lock-store failure is logged and retried next time. */
    public void runScheduled(AuditJob job) {
        try {
            runLocked(job);
        } catch (org.springframework.dao.DataAccessException unavailable) {
            log.error("Audit job could not start: job={}, cause={}", job, AuditDailyJob.technicalCause(unavailable));
        }
    }
}
