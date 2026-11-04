package com.memetrade.ledger.infra;

import com.memetrade.ledger.application.ClosingService;
import com.memetrade.ledger.infra.JdbcLedgerRepository.PositionRow;
import com.memetrade.marketdata.api.MarketCollectionCompleted;
import com.memetrade.shared.health.JobRounds;
import com.memetrade.shared.web.CorrelationId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * F4, the completion of the pending closes (BR5.3, NFR10.3, NFR10.4): after each market collection, at start-up and
 * every 60 s as a fallback, with a single run across instances (ShedLock {@code ledger-pending-close}). Each position
 * is handled in its own transaction; an account whose lock is not obtained in 1 s is skipped in this round and counted.
 * The round logs start, end, accounts handled and skipped, with identifiers only (NFR9.4).
 */
@Component
public class PendingCloseJob implements com.memetrade.ledger.api.LedgerJobs {

    private static final Logger log = LoggerFactory.getLogger(PendingCloseJob.class);
    static final String LOCK_NAME = "ledger-pending-close";
    static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(5);

    private final JdbcLedgerRepository repository;
    private final ClosingService closing;
    private final TransactionTemplate transactions;
    private final LockingTaskExecutor locks;
    private final Clock clock;
    private final boolean automatic;
    private final JobRounds rounds = new JobRounds();

    public PendingCloseJob(
            JdbcLedgerRepository repository,
            ClosingService closing,
            TransactionTemplate transactions,
            LockingTaskExecutor locks,
            Clock clock,
            @Value("${memetrade.scheduling.enabled:true}") boolean automatic) {
        this.repository = repository;
        this.closing = closing;
        this.transactions = transactions;
        this.locks = locks;
        this.clock = clock;
        this.automatic = automatic;
    }

    /** The result of one round: positions completed, skipped by a held lock, and failed. */
    public record Round(int completed, int skipped, int failed, boolean ran) {}

    @EventListener
    public void onStartup(ApplicationReadyEvent ready) {
        if (automatic) {
            runOnce();
        }
    }

    @EventListener
    public void onCollection(MarketCollectionCompleted completed) {
        if (automatic) {
            runOnce();
        }
    }

    @Scheduled(fixedDelayString = "${memetrade.ledger.marking-fallback-interval}", initialDelay = 60_000)
    public void fallback() {
        runOnce();
    }

    /** One round under the ShedLock lock; another instance (or round) holding it means this round does not run. */
    public Round runOnce() {
        return CorrelationId.withNew(() -> {
            LockConfiguration lock = new LockConfiguration(clock.instant(), LOCK_NAME, LOCK_AT_MOST_FOR, Duration.ZERO);
            try {
                LockingTaskExecutor.TaskResult<Round> result = locks.executeWithLock(this::round, lock);
                return result.wasExecuted() ? result.getResult() : new Round(0, 0, 0, false);
            } catch (RuntimeException | Error unexpected) {
                rounds.failed(clock.instant());
                throw unexpected;
            } catch (Throwable checked) {
                rounds.failed(clock.instant());
                throw new IllegalStateException("The pending close round failed with a checked exception", checked);
            }
        });
    }

    /**
     * Completes one pending position in its own transaction (used by the round and by the tests).
     *
     * @return true when this call completed it
     */
    public boolean completeOne(UUID botId, UUID positionId) {
        return Boolean.TRUE.equals(transactions.execute(status -> closing.completePending(botId, positionId)));
    }

    public JobRounds rounds() {
        return rounds;
    }

    @Override
    public JobRounds pendingCloseRounds() {
        return rounds;
    }

    private Round round() {
        Instant started = clock.instant();
        log.info("Pending close round started");
        List<PositionRow> pending = repository.pendingClosePositions();
        int completed = 0;
        int skipped = 0;
        int failed = 0;
        for (PositionRow position : pending) {
            try {
                if (completeOne(position.botId(), position.positionId())) {
                    completed++;
                }
            } catch (PessimisticLockingFailureException locked) {
                skipped++;
                log.info("Pending close skipped, the account is locked: botId={}", position.botId());
            } catch (DataAccessException | IllegalStateException failure) {
                failed++;
                log.error(
                        "Pending close failed: botId={}, exceptionClass={}",
                        position.botId(),
                        failure.getClass().getSimpleName());
            }
        }
        log.info(
                "Pending close round finished: pending={}, completed={}, skipped={}, failed={}, durationMs={}",
                pending.size(),
                completed,
                skipped,
                failed,
                Duration.between(started, clock.instant()).toMillis());
        if (failed > 0) {
            rounds.failed(started);
        } else {
            rounds.succeeded(started);
        }
        return new Round(completed, skipped, failed, true);
    }
}
