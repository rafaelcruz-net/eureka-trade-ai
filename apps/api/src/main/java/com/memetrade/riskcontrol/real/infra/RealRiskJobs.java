package com.memetrade.riskcontrol.real.infra;

import com.memetrade.botwallet.api.RealPauseReason;
import com.memetrade.botwallet.api.RealPauses;
import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.botwallet.api.RealStateView;
import com.memetrade.botwallet.api.RealWalletRef;
import com.memetrade.ledger.api.RealFlows;
import com.memetrade.ledger.api.RealLedgerView;
import com.memetrade.riskcontrol.real.domain.DaySnapshot;
import com.memetrade.riskcontrol.real.domain.RealRiskLimits;
import com.memetrade.riskcontrol.real.domain.RealRiskPolicy;
import com.memetrade.shared.web.CorrelationId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.function.Supplier;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 261113-trading-real-bot U3, the jobs F3, F4 and F5 of the REAL barrier (BR4.3 to BR4.5, BR6.2, U3-NFR4.4,
 * U3-NFR5.8). Each round runs once across instances (ShedLock, its own name), each wallet in a transaction of its own
 * (a failing wallet never stops the others, and the round is then not counted as a success), and publishes the
 * heartbeat of its job only after a successful round. Every round is idempotent and resumes by itself after a restart:
 * the photo is {@code ON CONFLICT DO NOTHING}, the pause and the resume of M1 are idempotent, the expiry moves only
 * OPEN rows. Scheduled in the worker by {@link RealRiskSchedule}; this bean exists in both processes so the tests and
 * the operators can run a round.
 */
@Component
public class RealRiskJobs {

    private static final Logger log = LoggerFactory.getLogger(RealRiskJobs.class);

    static final String DAY = "day";
    static final String DAILY_LOSS = "daily-loss";
    static final String RESERVATION_EXPIRY = "reservation-expiry";

    private final RealStateQuery states;
    private final RealPauses pauses;
    private final RealGateInputsReader reader;
    private final JdbcRealRiskStore store;
    private final RealRiskPolicy policy;
    private final RealRiskLimits limits;
    private final RealRiskMetrics metrics;
    private final LockingTaskExecutor locks;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public RealRiskJobs(
            RealStateQuery states,
            RealPauses pauses,
            RealGateInputsReader reader,
            JdbcRealRiskStore store,
            RealRiskPolicy policy,
            RealRiskLimits limits,
            RealRiskMetrics metrics,
            LockingTaskExecutor locks,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.states = states;
        this.pauses = pauses;
        this.reader = reader;
        this.store = store;
        this.policy = policy;
        this.limits = limits;
        this.metrics = metrics;
        this.locks = locks;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** F3: the photos of the day and the resume of the DAILY_LOSS pauses of a previous UTC day. */
    public record DayRound(boolean ran, int photos, int resumed, int failures) {}

    /** F4: the wallets paused by the loss of the day. */
    public record LossRound(boolean ran, int paused, int failures) {}

    /** F5: the reservations expired. */
    public record ExpiryRound(boolean ran, int expired) {}

    public DayRound runDay() {
        return locked(DAY, Duration.ofMinutes(4), this::dayRound, new DayRound(false, 0, 0, 0));
    }

    public LossRound runDailyLoss() {
        return locked(DAILY_LOSS, Duration.ofSeconds(50), this::lossRound, new LossRound(false, 0, 0));
    }

    public ExpiryRound runReservationExpiry() {
        return locked(RESERVATION_EXPIRY, Duration.ofSeconds(50), this::expiryRound, new ExpiryRound(false, 0));
    }

    private DayRound dayRound() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        int photos = 0;
        int resumed = 0;
        int failures = 0;
        for (RealWalletRef wallet : states.walletsInForce()) {
            try {
                Boolean written = transactions.execute(status -> photo(wallet));
                photos += Boolean.TRUE.equals(written) ? 1 : 0;
            } catch (RuntimeException failure) {
                failures++;
                warn(DAY, failure);
            }
        }
        for (RealStateQuery.PausedWallet paused : states.pausedWallets(RealPauseReason.DAILY_LOSS)) {
            if (!LocalDate.ofInstant(paused.since(), ZoneOffset.UTC).isBefore(today)) {
                continue;
            }
            try {
                RealPauses.PauseOutcome outcome = transactions.execute(status -> pauses.resumeUser(
                        paused.wallet().tenantId(), paused.wallet().userId(), RealPauseReason.DAILY_LOSS));
                resumed += outcome == RealPauses.PauseOutcome.RESUMED ? 1 : 0;
            } catch (RuntimeException failure) {
                failures++;
                warn(DAY, failure);
            }
        }
        log.info("REAL risk day job: photos={}, resumed={}, failures={}", photos, resumed, failures);
        return new DayRound(true, photos, resumed, failures);
    }

    /**
     * BR4.4: the photo with the value and the cap of this moment, unless the wallet already has one today. Review R-01:
     * {@code takenAt} is the instant read just BEFORE the read of THIS wallet (one read of the ledger,
     * {@link RealLedgerView}), never the start of the round: a flow recorded after it is counted by
     * {@code flowsSince(takenAt)}, and one already inside the value of the photo can only make the loss look larger
     * (the safe side), never hide it. The day of the photo is the UTC day of that same instant.
     */
    private boolean photo(RealWalletRef wallet) {
        Instant takenAt = clock.instant();
        LocalDate day = LocalDate.ofInstant(takenAt, ZoneOffset.UTC);
        if (store.snapshot(wallet.tenantId(), wallet.botWalletId(), day).isPresent()) {
            return false;
        }
        Optional<RealStateView> state = states.readRealState(wallet.tenantId(), wallet.userId());
        if (state.isEmpty()) {
            return false;
        }
        long value = reader.value(wallet.tenantId(), wallet.botWalletId(), Optional.empty())
                .value();
        return store.insertSnapshot(new DaySnapshot(
                wallet.tenantId(),
                wallet.botWalletId(),
                day,
                value,
                state.get().capLamports(),
                takenAt,
                DaySnapshot.TakenBy.DAY_JOB));
    }

    private LossRound lossRound() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        int paused = 0;
        int failures = 0;
        for (RealWalletRef wallet : states.walletsInForce()) {
            try {
                Boolean pausedNow = transactions.execute(status -> judge(wallet, today));
                if (Boolean.TRUE.equals(pausedNow)) {
                    paused++;
                    metrics.dailyLossPause();
                }
            } catch (RuntimeException failure) {
                failures++;
                warn(DAILY_LOSS, failure);
            }
        }
        if (paused > 0) {
            log.info("REAL risk loss job: paused={}, failures={}", paused, failures);
        }
        return new LossRound(true, paused, failures);
    }

    /** BR4.3: the same loss as the buy; only a wallet with the photo of today and without the DAILY_LOSS pause. */
    private boolean judge(RealWalletRef wallet, LocalDate today) {
        Optional<RealStateView> state = states.readRealState(wallet.tenantId(), wallet.userId());
        if (state.isEmpty() || state.get().pausedReasons().contains(RealPauseReason.DAILY_LOSS)) {
            return false;
        }
        Optional<DaySnapshot> snapshot = store.snapshot(wallet.tenantId(), wallet.botWalletId(), today);
        if (snapshot.isEmpty()) {
            return false;
        }
        // Review R-01: the value and the flows after the photo from ONE read of the ledger.
        RealGateInputsReader.WalletValue now = reader.value(
                wallet.tenantId(),
                wallet.botWalletId(),
                Optional.of(snapshot.get().takenAt()));
        RealFlows flows = now.flows();
        if (!policy.dailyLoss(snapshot.get(), flows, now.value(), state.get().capLamports(), limits)
                .breached()) {
            return false;
        }
        return pauses.pauseUser(wallet.tenantId(), wallet.userId(), RealPauseReason.DAILY_LOSS)
                == RealPauses.PauseOutcome.PAUSED;
    }

    private ExpiryRound expiryRound() {
        Integer expired = transactions.execute(status -> store.expireReservations(clock.instant()));
        int count = expired == null ? 0 : expired;
        metrics.reservationsExpired(count);
        if (count > 0) {
            log.warn("REAL risk reservations expired without the hand-over of the U4: count={}", count);
        }
        return new ExpiryRound(true, count);
    }

    /**
     * One round under ShedLock {@code real-risk-<job>}. The heartbeat moves only after a round without failure; a
     * failure of the whole round is logged and rethrown (the scheduler logs it and the next round runs again).
     */
    private <T> T locked(String job, Duration atMost, Supplier<T> round, T skipped) {
        return CorrelationId.withNew(() -> {
            LockConfiguration lock = new LockConfiguration(clock.instant(), "real-risk-" + job, atMost, Duration.ZERO);
            long started = System.nanoTime();
            try {
                LockingTaskExecutor.TaskResult<T> result = locks.executeWithLock(round::get, lock);
                if (!result.wasExecuted()) {
                    return skipped;
                }
                T done = result.getResult();
                if (!failed(done)) {
                    metrics.jobSucceeded(job, Duration.ofNanos(System.nanoTime() - started), clock.instant());
                }
                return done;
            } catch (RuntimeException | Error unexpected) {
                warn(job, unexpected);
                throw unexpected;
            } catch (Throwable checked) {
                throw new IllegalStateException("The REAL risk job " + job + " failed", checked);
            }
        });
    }

    private static boolean failed(Object round) {
        return (round instanceof DayRound day && day.failures() > 0)
                || (round instanceof LossRound loss && loss.failures() > 0);
    }

    private static void warn(String job, Throwable failure) {
        log.warn(
                "REAL risk job failed for one wallet or round: job={}, exceptionClass={}",
                job,
                failure.getClass().getSimpleName());
    }
}
