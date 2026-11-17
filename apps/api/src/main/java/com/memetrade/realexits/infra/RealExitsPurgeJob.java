package com.memetrade.realexits.infra;

import com.memetrade.riskcontrol.infra.RiskRetentionDataSource;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.web.CorrelationId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 261113-trading-real-bot U7 (U7-NFR3.7, scalability-design.md): the daily purge of the REAL exits, at 05:30 UTC in the
 * api (after the purges of the U3, the U4 and the U5), once across instances (ShedLock {@code real-exits-purge}, at most
 * 45 minutes), through the retention pool of the U6 (the retention login, member of {@code risk_retention}; the one
 * named exception of the module, as in the U3 to the U5). The conditionals that ended more than 5 years (and a day)
 * ago, the fiscal deadline, are deleted in batches of at most 1.000 in auto-commit until a batch comes back short or
 * 30 minutes passed; a conditional that is not final is never selected. The age trigger of the migration 0022 checks the
 * role and the age again: a wrong selection fails the round without deleting a recent row. Logs carry only totals.
 */
@Component
@ProcessRole(ProcessRole.Kind.API)
public class RealExitsPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(RealExitsPurgeJob.class);

    static final String LOCK_NAME = "real-exits-purge";
    static final String SUCCEEDED = "SUCCEEDED";
    static final String FAILED = "FAILED";
    static final int BATCH_SIZE = 1000;
    static final Duration MAX_RUN = Duration.ofMinutes(30);
    static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(45);

    /** One day more than the 5 years the age trigger enforces. */
    static final String SELECTION = "5 years 1 day";

    private final RiskRetentionDataSource retention;
    private final LockingTaskExecutor locks;
    private final RealExitsMetrics metrics;
    private final Clock clock;
    private final String selection;

    @Autowired
    public RealExitsPurgeJob(
            RiskRetentionDataSource retention, LockingTaskExecutor locks, RealExitsMetrics metrics, Clock clock) {
        this(retention, locks, metrics, clock, SELECTION);
    }

    /** @param selection a PostgreSQL interval (the tests prove the trigger with less) */
    RealExitsPurgeJob(
            RiskRetentionDataSource retention,
            LockingTaskExecutor locks,
            RealExitsMetrics metrics,
            Clock clock,
            String selection) {
        this.retention = retention;
        this.locks = locks;
        this.metrics = metrics;
        this.clock = clock;
        this.selection = selection;
    }

    /**
     * One round.
     *
     * @param ran false when another instance held the lock (nothing done)
     * @param result {@code SUCCEEDED} or {@code FAILED}; empty when the round did not run
     */
    public record Round(boolean ran, String result, long deleted, int batches, Optional<String> failureCause) {}

    @Scheduled(cron = "0 30 5 * * *", zone = "UTC")
    public void scheduled() {
        runOnce();
    }

    public Round runOnce() {
        return CorrelationId.withNew(() -> {
            LockConfiguration lock = new LockConfiguration(clock.instant(), LOCK_NAME, LOCK_AT_MOST_FOR, Duration.ZERO);
            try {
                LockingTaskExecutor.TaskResult<Round> result = locks.executeWithLock(this::round, lock);
                if (!result.wasExecuted()) {
                    log.info("REAL exits purge skipped: another instance holds the lock");
                    return new Round(false, "", 0, 0, Optional.empty());
                }
                return result.getResult();
            } catch (RuntimeException | Error unexpected) {
                throw unexpected;
            } catch (Throwable checked) {
                throw new IllegalStateException("The REAL exits purge failed with a checked exception", checked);
            }
        });
    }

    private Round round() {
        Instant deadline = clock.instant().plus(MAX_RUN);
        long deleted = 0;
        int batches = 0;
        try {
            while (clock.instant().isBefore(deadline)) {
                int batch = deleteBatch();
                batches++;
                deleted += batch;
                if (batch < BATCH_SIZE) {
                    break;
                }
            }
            metrics.jobRan("purge");
            log.info("REAL exits purge finished: deleted={}, batches={}", deleted, batches);
            return new Round(true, SUCCEEDED, deleted, batches, Optional.empty());
        } catch (RuntimeException failure) {
            String cause = failure.getClass().getSimpleName();
            log.error(
                    "REAL exits purge failed: cause={}, deletedBeforeFailure={}, batches={}",
                    cause,
                    deleted,
                    batches,
                    failure);
            return new Round(true, FAILED, deleted, batches, Optional.of(cause));
        }
    }

    private int deleteBatch() {
        return retention
                .jdbc()
                .sql("""
                        delete from real_conditional_order where conditional_order_id in (
                          select conditional_order_id from real_conditional_order
                          where final_at is not null and final_at < now() - cast(:selection as interval)
                          order by final_at limit :limit)""")
                .param("selection", selection)
                .param("limit", BATCH_SIZE)
                .update();
    }
}
