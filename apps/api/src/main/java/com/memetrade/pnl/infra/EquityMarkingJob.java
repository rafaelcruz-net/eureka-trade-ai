package com.memetrade.pnl.infra;

import com.memetrade.ledger.api.LedgerReadModel;
import com.memetrade.ledger.api.LedgerReadModel.AccountView;
import com.memetrade.marketdata.api.MarketCollectionCompleted;
import com.memetrade.pnl.application.EquityPeakUpdater;
import com.memetrade.shared.health.JobRounds;
import com.memetrade.shared.web.CorrelationId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * F5, the marking of every OPEN or CLOSING account and its equity peak (BR7.3), after each market collection and every
 * 60 s as a fallback, single run across instances (ShedLock {@code pnl-equity-marking}). One transaction per account,
 * which locks the account first; an account whose lock is not obtained in 1 s is skipped in this round (NFR10.7). Logs
 * carry counts and identifiers only (NFR9.4).
 */
@Component
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class EquityMarkingJob {

    private static final Logger log = LoggerFactory.getLogger(EquityMarkingJob.class);
    static final String LOCK_NAME = "pnl-equity-marking";
    static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(5);

    private final LedgerReadModel ledger;
    private final EquityPeakUpdater updater;
    private final TransactionTemplate transactions;
    private final LockingTaskExecutor locks;
    private final Clock clock;
    private final boolean automatic;
    private final JobRounds rounds = new JobRounds();

    public EquityMarkingJob(
            LedgerReadModel ledger,
            EquityPeakUpdater updater,
            TransactionTemplate transactions,
            LockingTaskExecutor locks,
            Clock clock,
            @Value("${memetrade.scheduling.enabled:true}") boolean automatic) {
        this.ledger = ledger;
        this.updater = updater;
        this.transactions = transactions;
        this.locks = locks;
        this.clock = clock;
        this.automatic = automatic;
    }

    /** Accounts marked, skipped by a held lock, failed; {@code ran} is false when another round held the lock. */
    public record Round(int marked, int skipped, int failed, boolean ran) {}

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
                throw new IllegalStateException("The marking round failed with a checked exception", checked);
            }
        });
    }

    public JobRounds rounds() {
        return rounds;
    }

    private Round round() {
        Instant started = clock.instant();
        log.info("Equity marking round started");
        List<AccountView> accounts = ledger.accountsToMark();
        int marked = 0;
        int skipped = 0;
        int failed = 0;
        for (AccountView account : accounts) {
            try {
                transactions.executeWithoutResult(status -> updater.mark(account.botId()));
                marked++;
            } catch (PessimisticLockingFailureException locked) {
                skipped++;
                log.info("Equity marking skipped, the account is locked: botId={}", account.botId());
            } catch (DataAccessException | IllegalStateException failure) {
                failed++;
                log.error(
                        "Equity marking failed: botId={}, exceptionClass={}",
                        account.botId(),
                        failure.getClass().getSimpleName());
            }
        }
        log.info(
                "Equity marking round finished: accounts={}, marked={}, skipped={}, failed={}, durationMs={}",
                accounts.size(),
                marked,
                skipped,
                failed,
                Duration.between(started, clock.instant()).toMillis());
        if (failed > 0) {
            rounds.failed(started);
        } else {
            rounds.succeeded(started);
        }
        return new Round(marked, skipped, failed, true);
    }
}
