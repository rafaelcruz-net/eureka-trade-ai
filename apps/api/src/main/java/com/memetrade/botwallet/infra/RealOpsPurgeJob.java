package com.memetrade.botwallet.infra;

import com.memetrade.riskcontrol.infra.RiskRetentionDataSource;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.web.CorrelationId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 261113-trading-real-bot U5 (U5-NFR3.9, scalability-design.md, reliability-design.md, nfr-design review R-09): the daily
 * purge of the operations of the REAL, at 05:00 UTC in the api (after the purges of the U3 at 04:00 and the U4 at 04:30),
 * once across instances (ShedLock {@code real-ops-purge}, at most 45 minutes), through the retention pool (the retention
 * login, member of {@code risk_retention}; the only class of {@code botwallet} allowed to receive it).
 *
 * <ol>
 *   <li>The divergences RESOLVED more than 5 years (and a day) ago go first, so their reconciliations become free in the
 *       same round.
 *   <li>The reconciliations older than 13 months (and a day) go when no divergence references them, by {@code run_id} or
 *       {@code last_run_id} ({@code NOT EXISTS}): the {@code ON DELETE RESTRICT} is never hit and the batch never stalls.
 *   <li>The commands to the TxSigner confirmed more than 13 months (and a day) ago; a pending one is never selected.
 *   <li>The releases revoked more than 5 years (and a day) ago; a release in force is never selected.
 * </ol>
 *
 * <p>Each table in batches of at most 1.000 rows in auto-commit, until a batch comes back short or 30 minutes passed. The
 * age triggers of the migration 0021 check the role and the age again; the day of margin makes every selected row pass
 * them, and a wrong selection fails the round without deleting a recent row. Every round logs its totals (never an
 * address) and a successful one counts the job {@code purge}.
 */
@Component
@ProcessRole(ProcessRole.Kind.API)
public class RealOpsPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(RealOpsPurgeJob.class);

    static final String LOCK_NAME = "real-ops-purge";
    static final String JOB = "purge";
    static final String SUCCEEDED = "SUCCEEDED";
    static final String FAILED = "FAILED";
    static final int BATCH_SIZE = 1000;
    static final Duration MAX_RUN = Duration.ofMinutes(30);
    static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(45);

    /** One day more than the 13 months the triggers of the reconciliations and the commands enforce. */
    static final String SHORT_SELECTION = "13 months 1 day";

    /** One day more than the 5 years the triggers of the divergences and the releases enforce. */
    static final String LONG_SELECTION = "5 years 1 day";

    /** One table of the purge: its key, the instant that ages it, the retention it follows and an extra filter. */
    private record Target(String table, String key, String instant, boolean longRetention, String filter) {}

    private static final List<Target> TARGETS = List.of(
            new Target("real_divergence", "divergence_id", "resolved_at", true, " and state = 'RESOLVED'"),
            new Target(
                    "real_reconciliation_run",
                    "run_id",
                    "started_at",
                    false,
                    " and not exists (select 1 from real_divergence d where d.run_id = t.run_id"
                            + " or d.last_run_id = t.run_id)"),
            new Target(
                    "real_pending_txsigner_command",
                    "command_id",
                    "confirmed_at",
                    false,
                    " and confirmed_at is not null"),
            new Target("real_release", "release_id", "revoked_at", true, " and revoked_at is not null"));

    private final RiskRetentionDataSource retention;
    private final LockingTaskExecutor locks;
    private final RealOpsMetrics metrics;
    private final Clock clock;
    private final String shortSelection;
    private final String longSelection;

    @Autowired
    public RealOpsPurgeJob(
            RiskRetentionDataSource retention, LockingTaskExecutor locks, RealOpsMetrics metrics, Clock clock) {
        this(retention, locks, metrics, clock, SHORT_SELECTION, LONG_SELECTION);
    }

    /** @param shortSelection and {@code longSelection}: PostgreSQL intervals (tests prove the triggers with less) */
    RealOpsPurgeJob(
            RiskRetentionDataSource retention,
            LockingTaskExecutor locks,
            RealOpsMetrics metrics,
            Clock clock,
            String shortSelection,
            String longSelection) {
        this.retention = retention;
        this.locks = locks;
        this.metrics = metrics;
        this.clock = clock;
        this.shortSelection = shortSelection;
        this.longSelection = longSelection;
    }

    /**
     * One round.
     *
     * @param ran false when another instance held the lock (nothing done)
     * @param result {@code SUCCEEDED} or {@code FAILED}; empty when the round did not run
     */
    public record Round(boolean ran, String result, long deleted, int batches, Optional<String> failureCause) {}

    @Scheduled(cron = "0 0 5 * * *", zone = "UTC")
    public void scheduled() {
        runOnce();
    }

    public Round runOnce() {
        return CorrelationId.withNew(() -> {
            LockConfiguration lock = new LockConfiguration(clock.instant(), LOCK_NAME, LOCK_AT_MOST_FOR, Duration.ZERO);
            try {
                LockingTaskExecutor.TaskResult<Round> result = locks.executeWithLock(this::round, lock);
                if (!result.wasExecuted()) {
                    log.info("REAL operations purge skipped: another instance holds the lock");
                    return new Round(false, "", 0, 0, Optional.empty());
                }
                return result.getResult();
            } catch (RuntimeException | Error unexpected) {
                throw unexpected;
            } catch (Throwable checked) {
                throw new IllegalStateException("The REAL operations purge failed with a checked exception", checked);
            }
        });
    }

    private Round round() {
        Instant started = clock.instant();
        Instant deadline = started.plus(MAX_RUN);
        long startedNanos = System.nanoTime();
        long deleted = 0;
        int batches = 0;
        try {
            for (Target target : TARGETS) {
                while (clock.instant().isBefore(deadline)) {
                    int batch = deleteBatch(target);
                    batches++;
                    deleted += batch;
                    if (batch < BATCH_SIZE) {
                        break;
                    }
                }
            }
            metrics.jobRan(JOB, Duration.ofNanos(System.nanoTime() - startedNanos));
            log.info("REAL operations purge finished: deleted={}, batches={}", deleted, batches);
            return new Round(true, SUCCEEDED, deleted, batches, Optional.empty());
        } catch (RuntimeException failure) {
            String cause = failure.getClass().getSimpleName();
            log.error(
                    "REAL operations purge failed: cause={}, deletedBeforeFailure={}, batches={}",
                    cause,
                    deleted,
                    batches,
                    failure);
            return new Round(true, FAILED, deleted, batches, Optional.of(cause));
        }
    }

    private int deleteBatch(Target target) {
        String sql = "delete from " + target.table() + " where " + target.key() + " in (select t." + target.key()
                + " from " + target.table() + " t where t." + target.instant()
                + " < now() - cast(:selection as interval)" + target.filter() + " order by t." + target.instant()
                + " limit :limit)";
        return retention
                .jdbc()
                .sql(sql)
                .param("selection", target.longRetention() ? longSelection : shortSelection)
                .param("limit", BATCH_SIZE)
                .update();
    }
}
