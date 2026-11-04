package com.memetrade.pnl.infra;

import com.memetrade.ledger.api.LedgerReadModel;
import com.memetrade.ledger.api.LedgerReadModel.PersonRef;
import com.memetrade.pnl.application.ReconciliationService;
import com.memetrade.pnl.application.ReconciliationService.DivergenceKind;
import com.memetrade.pnl.application.ReconciliationService.Result;
import com.memetrade.pnl.application.ReconciliationService.Trigger;
import com.memetrade.shared.health.JobRounds;
import com.memetrade.shared.web.CorrelationId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * F9, the scheduled reconciliation every 5 minutes for each person with a PAPER account (BR8.1), single run across
 * instances (ShedLock {@code pnl-reconciliation}). Tenant and person come from the account rows; each person is
 * checked alone. A round with divergences logs a WARN with the number of people and the count per kind only
 * (NFR9.7), never a person, a bot or a value; the same count reaches the health indicator {@code ledgerJobs}.
 */
@Component
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);
    static final String LOCK_NAME = "pnl-reconciliation";
    static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(10);

    private final LedgerReadModel ledger;
    private final ReconciliationService reconciliation;
    private final LockingTaskExecutor locks;
    private final Clock clock;
    private final JobRounds rounds = new JobRounds();
    private volatile int lastDivergentPeople;

    public ReconciliationJob(
            LedgerReadModel ledger, ReconciliationService reconciliation, LockingTaskExecutor locks, Clock clock) {
        this.ledger = ledger;
        this.reconciliation = reconciliation;
        this.locks = locks;
        this.clock = clock;
    }

    /** People checked, people with divergences, failures; {@code ran} is false when another round held the lock. */
    public record Round(int people, int divergentPeople, int failed, boolean ran) {}

    @Scheduled(fixedDelayString = "${memetrade.ledger.reconciliation-interval}", initialDelay = 60_000)
    public void scheduled() {
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
                throw new IllegalStateException("The reconciliation round failed with a checked exception", checked);
            }
        });
    }

    public JobRounds rounds() {
        return rounds;
    }

    /** People with divergences in the last round (health detail). */
    public int lastDivergentPeople() {
        return lastDivergentPeople;
    }

    private Round round() {
        Instant started = clock.instant();
        log.info("Reconciliation round started");
        Map<DivergenceKind, Integer> byKind = new EnumMap<>(DivergenceKind.class);
        int people = 0;
        int divergent = 0;
        int failed = 0;
        for (PersonRef person : ledger.peopleWithAccounts()) {
            try {
                Result result = reconciliation.check(person.tenantId(), person.ownerUserId(), Trigger.SCHEDULED);
                people++;
                if (!result.reconciled()) {
                    divergent++;
                    result.divergences().forEach(d -> byKind.merge(d.kind(), 1, Integer::sum));
                }
            } catch (DataAccessException | IllegalStateException failure) {
                failed++;
                log.error(
                        "Reconciliation of one person failed: exceptionClass={}",
                        failure.getClass().getSimpleName());
            }
        }
        lastDivergentPeople = divergent;
        if (divergent > 0) {
            log.warn("reconciliation found divergences: people={}, byKind={}", divergent, byKind);
        }
        // NFR3.6: counts and the duration only, never a person, a bot or a value (NFR9.7).
        log.info(
                "Reconciliation round finished: people={}, divergentPeople={}, failed={}, durationMs={}",
                people,
                divergent,
                failed,
                Duration.between(started, clock.instant()).toMillis());
        if (failed > 0) {
            rounds.failed(started);
        } else {
            rounds.succeeded(started);
        }
        return new Round(people, divergent, failed, true);
    }
}
