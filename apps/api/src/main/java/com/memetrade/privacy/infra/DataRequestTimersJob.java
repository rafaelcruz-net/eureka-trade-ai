package com.memetrade.privacy.infra;

import com.memetrade.privacy.domain.request.DataRequestTimers;
import com.memetrade.shared.web.CorrelationId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.IntSupplier;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockingTaskExecutor.TaskResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The job of 15 minutes of the requests (F4 step 1; BR4.13, BR4.14; NFR3.4, NFR3.7, NFR7.2): under the ShedLock lock
 * {@code dataRequestTimers} (at most 5 minutes, at least 1), each batch of {@link DataRequestTimers} runs in a
 * transaction of its own ({@code REQUIRES_NEW}) with its events, first the expired verifications then the overdue
 * requests. The conditional updates make a repeated or concurrent run harmless. Every exception is caught here and
 * logged with its class only: nothing reaches the scheduler and the api goes on.
 */
public class DataRequestTimersJob {

    /** The name of the ShedLock lock (NFR3.7). */
    public static final String LOCK_NAME = "dataRequestTimers";

    private static final Logger log = LoggerFactory.getLogger(DataRequestTimersJob.class);

    private final LockingTaskExecutor locks;
    private final DataRequestTimers timers;
    private final TransactionTemplate ownTransaction;
    private final PrivacyProperties settings;
    private final Clock clock;

    public DataRequestTimersJob(
            LockingTaskExecutor locks,
            DataRequestTimers timers,
            PlatformTransactionManager transactions,
            PrivacyProperties settings,
            Clock clock) {
        this.locks = locks;
        this.timers = timers;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * One run when the lock is free.
     *
     * @return the rows changed, or empty when another instance holds the lock or the run failed
     */
    public Optional<DataRequestTimers.Outcome> runOnce() {
        LockConfiguration lock = new LockConfiguration(
                clock.instant(), LOCK_NAME, settings.timersLockAtMostFor(), settings.timersLockAtLeastFor());
        try {
            TaskResult<DataRequestTimers.Outcome> result =
                    locks.executeWithLock(() -> CorrelationId.withNew(this::run), lock);
            return result.wasExecuted() ? Optional.of(result.getResult()) : Optional.empty();
        } catch (DataAccessException | IllegalStateException failed) {
            log.error("Data request timers failed: cause={}", PrivacyJobRunner.technicalCause(failed));
            return Optional.empty();
        } catch (RuntimeException | Error unexpected) {
            throw unexpected;
        } catch (Throwable checked) {
            throw new IllegalStateException("The data request timers failed with a checked exception", checked);
        }
    }

    private DataRequestTimers.Outcome run() {
        Instant now = clock.instant();
        int refused = drain(() -> inTransaction(() -> timers.refuseBatch(now, timers.batchSize())));
        int notified = drain(() -> inTransaction(() -> timers.notifyBatch(now, timers.batchSize())));
        log.info(
                "Data request timers finished: refused={}, overdueNotified={}, durationMs={}",
                refused,
                notified,
                Duration.between(now, clock.instant()).toMillis());
        return new DataRequestTimers.Outcome(refused, notified);
    }

    private int inTransaction(IntSupplier batch) {
        Integer rows = ownTransaction.execute(status -> batch.getAsInt());
        if (rows == null) {
            throw new IllegalStateException("A timers batch returned no count");
        }
        return rows;
    }

    private int drain(IntSupplier batch) {
        int total = 0;
        while (true) {
            int rows = batch.getAsInt();
            total += rows;
            if (rows < timers.batchSize()) {
                return total;
            }
        }
    }
}
