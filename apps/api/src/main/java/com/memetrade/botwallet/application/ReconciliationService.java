package com.memetrade.botwallet.application;

import com.memetrade.botwallet.domain.ChainReading;
import com.memetrade.botwallet.domain.LedgerSnapshot;
import com.memetrade.botwallet.domain.ReconciliationInputs;
import com.memetrade.botwallet.domain.ReconciliationOutcome;
import com.memetrade.botwallet.domain.ReconciliationPolicy;
import com.memetrade.botwallet.domain.ReconciliationResult;
import com.memetrade.botwallet.domain.ReconciliationStore;
import com.memetrade.botwallet.domain.ReconciliationStore.Candidate;
import com.memetrade.botwallet.domain.ReconciliationStore.RunRecord;
import com.memetrade.botwallet.domain.ReconciliationStore.WalletTarget;
import com.memetrade.botwallet.domain.ReconciliationTrigger;
import com.memetrade.botwallet.infra.GlobalPauses;
import com.memetrade.botwallet.infra.RealOpsMetrics;
import com.memetrade.botwallet.infra.RealOpsProperties;
import com.memetrade.botwallet.infra.ReconciliationReader;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.realexecution.api.RealExecutionPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 261113-trading-real-bot U5 (F3, BR2.1, BR2.2, U5-NFR3.1, U5-NFR4.3): the reconciliation of one bot wallet. In transit
 * (an order SENT or AMBIGUOUS of the person through the M6, a withdrawal SENT or AMBIGUOUS of the wallet) the network is
 * not read; otherwise the network (through the ChainPort of the U1) and the REAL ledger (M2, never PAPER) are compared
 * by the pure {@link ReconciliationPolicy}. The run and what it changes in the divergences commit together.
 *
 * <p>{@link #dueWallets()} says which ACTIVE wallets are due: a restore asks AFTER_RESTORE, a compensation
 * AFTER_COMPENSATION, a confirmed order or withdrawal (a mark newer than the last run) ORDER_CONFIRMED, and every
 * {@code reconciliation-interval} (15 min) SCHEDULE.
 */
@Service("realReconciliationService")
public class ReconciliationService {

    private final ReconciliationStore store;
    private final ReconciliationReader reader;
    private final RealLedger ledger;
    private final RealExecutionPort execution;
    private final DivergenceService divergences;
    private final RealOpsMetrics metrics;
    private final Duration interval;
    private final GlobalPauses pauses;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ReconciliationService(
            ReconciliationStore store,
            ReconciliationReader reader,
            RealLedger ledger,
            RealExecutionPort execution,
            DivergenceService divergences,
            RealOpsMetrics metrics,
            RealOpsProperties ops,
            GlobalPauses pauses,
            TransactionTemplate transactions,
            Clock clock) {
        this.store = store;
        this.reader = reader;
        this.ledger = ledger;
        this.execution = execution;
        this.divergences = divergences;
        this.metrics = metrics;
        this.interval = ops.reconciliationInterval();
        this.pauses = pauses;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** One reconciliation of the wallet; returns the run recorded. */
    public RunView reconcile(UUID botWalletId, ReconciliationTrigger trigger) {
        long started = System.nanoTime();
        Instant startedAt = clock.instant();
        WalletTarget wallet =
                store.target(botWalletId).orElseThrow(() -> new IllegalArgumentException("No bot wallet to reconcile"));
        int orders = execution.ordersInTransit(wallet.tenantId(), wallet.userId());
        int withdrawals = store.withdrawalsInTransit(botWalletId);
        Optional<ChainReading> chain = orders + withdrawals > 0 || wallet.address() == null
                ? Optional.empty()
                : reader.read(wallet.address(), wallet.depositCursor());
        LedgerSnapshot snapshot = new LedgerSnapshot(
                ledger.balance(wallet.tenantId(), botWalletId).totalLamports(),
                ledger.tokenBalances(wallet.tenantId(), botWalletId));
        ReconciliationOutcome outcome =
                ReconciliationPolicy.decide(new ReconciliationInputs(orders, withdrawals, chain, snapshot));
        RunRecord run = new RunRecord(
                UUID.randomUUID(), wallet.tenantId(), botWalletId, trigger, outcome, startedAt, clock.instant());
        transactions.executeWithoutResult(status -> {
            store.insertRun(run);
            divergences.onRun(wallet, run);
        });
        metrics.reconciled(outcome.result().name(), Duration.ofNanos(System.nanoTime() - started));
        return new RunView(run.runId(), outcome.result());
    }

    /** The ACTIVE wallets due now, with the trigger of their reconciliation. */
    public List<DueWallet> dueWallets() {
        Instant now = clock.instant();
        Optional<Instant> restoredAt = pauses.openRestoreMarker()
                .filter(GlobalPauses.OpenMarker::restore)
                .map(GlobalPauses.OpenMarker::detectedAt);
        List<DueWallet> due = new ArrayList<>();
        for (Candidate candidate : store.candidates()) {
            trigger(candidate, now, restoredAt)
                    .ifPresent(trigger -> due.add(new DueWallet(candidate.botWalletId(), trigger)));
        }
        return due;
    }

    private Optional<ReconciliationTrigger> trigger(Candidate candidate, Instant now, Optional<Instant> restoredAt) {
        Instant last = candidate.lastRun();
        // BR2.5: after a restore, every wallet once more, completely (AFTER_RESTORE).
        if (restoredAt.isPresent()
                && (candidate.lastRestoreRun() == null
                        || candidate.lastRestoreRun().isBefore(restoredAt.get()))) {
            return Optional.of(ReconciliationTrigger.AFTER_RESTORE);
        }
        if (candidate.compensatedAt() != null
                && (last == null || candidate.compensatedAt().isAfter(last))) {
            return Optional.of(ReconciliationTrigger.AFTER_COMPENSATION);
        }
        if (last == null || !now.isBefore(last.plus(interval))) {
            return Optional.of(ReconciliationTrigger.SCHEDULE);
        }
        boolean marked = candidate.lastMark() != null && candidate.lastMark().isAfter(last);
        if (marked || execution.changeMark(candidate.tenantId(), candidate.userId()) > last.toEpochMilli()) {
            return Optional.of(ReconciliationTrigger.ORDER_CONFIRMED);
        }
        return Optional.empty();
    }

    /** One recorded run. */
    public record RunView(UUID runId, ReconciliationResult result) {}

    /** One wallet due and why. */
    public record DueWallet(UUID botWalletId, ReconciliationTrigger trigger) {}
}
