package com.memetrade.pnl.application;

import com.memetrade.ledger.api.LedgerReadModel;
import com.memetrade.ledger.api.LedgerReadModel.OrderFacts;
import com.memetrade.ledger.api.LedgerReadModel.PositionFacts;
import com.memetrade.ledger.api.LedgerReadModel.ReconciliationFacts;
import com.memetrade.ledger.api.LedgerReadModel.SummaryFacts;
import com.memetrade.pnl.infra.JdbcReconciliationStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * F9, the reconciliation of one person (BR8.1 to BR8.3, NFR10.6): every FILLED or PARTIALLY_FILLED order against its
 * entries (a valid compensation enters the expected and the found, so it never diverges), orphan entries, positions
 * against the replay of their fills and the daily summary against the direct sum. Reads one REPEATABLE READ snapshot
 * (statement timeout 30 s), records the check and keeps the last 100 per person.
 */
@Service
public class ReconciliationService {

    public static final int KEEP_PER_PERSON = 100;

    public enum Trigger {
        SCHEDULED,
        ON_DEMAND
    }

    /** C5 {@code divergences.kind}, with the U7 additions POSITION_MISMATCH and SUMMARY_MISMATCH. */
    public enum DivergenceKind {
        MISSING_ENTRY,
        AMOUNT_MISMATCH,
        ORPHAN_ENTRY,
        POSITION_MISMATCH,
        SUMMARY_MISMATCH
    }

    public record Divergence(
            UUID botId,
            Optional<UUID> orderId,
            Optional<UUID> positionId,
            Optional<UUID> entryId,
            Optional<LocalDate> utcDay,
            DivergenceKind kind,
            BigDecimal expected,
            BigDecimal found) {}

    public record Result(UUID checkId, Trigger trigger, Instant checkedAt, List<Divergence> divergences) {
        public Result {
            divergences = List.copyOf(divergences);
        }

        public boolean reconciled() {
            return divergences.isEmpty();
        }
    }

    private final LedgerReadModel ledger;
    private final JdbcReconciliationStore store;
    private final Clock clock;

    public ReconciliationService(LedgerReadModel ledger, JdbcReconciliationStore store, Clock clock) {
        this.ledger = ledger;
        this.store = store;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Result check(UUID tenantId, UUID ownerUserId, Trigger trigger) {
        store.setStatementTimeout();
        List<Divergence> divergences = compare(ledger.reconciliationFacts(tenantId, ownerUserId));
        Result result = new Result(UUID.randomUUID(), trigger, clock.instant(), divergences);
        store.insert(tenantId, ownerUserId, result);
        store.keepLatest(tenantId, ownerUserId, KEEP_PER_PERSON);
        return result;
    }

    @Transactional(readOnly = true)
    public Optional<Result> latest(UUID tenantId, UUID ownerUserId) {
        return store.latest(tenantId, ownerUserId);
    }

    /** True when the person has a PAPER account (every account starts with its FUNDING entry). */
    @Transactional(readOnly = true)
    public boolean hasAccounts(UUID tenantId, UUID ownerUserId) {
        return ledger.entries(tenantId, ownerUserId, Optional.empty(), Optional.empty(), 1)
                        .total()
                > 0;
    }

    static List<Divergence> compare(ReconciliationFacts facts) {
        List<Divergence> divergences = new ArrayList<>();
        for (OrderFacts order : facts.orders()) {
            if (order.entryCount() == 0) {
                divergences.add(new Divergence(
                        order.botId(),
                        Optional.of(order.orderId()),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        DivergenceKind.MISSING_ENTRY,
                        order.expected(),
                        order.found()));
            } else if (order.expected().compareTo(order.found()) != 0) {
                divergences.add(new Divergence(
                        order.botId(),
                        Optional.of(order.orderId()),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        DivergenceKind.AMOUNT_MISMATCH,
                        order.expected(),
                        order.found()));
            }
        }
        facts.orphans()
                .forEach(orphan -> divergences.add(new Divergence(
                        orphan.botId(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(orphan.entryId()),
                        Optional.empty(),
                        DivergenceKind.ORPHAN_ENTRY,
                        BigDecimal.ZERO.setScale(6),
                        orphan.amount())));
        for (PositionFacts position : facts.positions()) {
            boolean quantity = position.quantity().compareTo(position.replayedQuantity()) != 0;
            boolean cost = position.costBasis().compareTo(position.replayedCostBasis()) != 0;
            if (quantity || cost) {
                divergences.add(new Divergence(
                        position.botId(),
                        Optional.empty(),
                        Optional.of(position.positionId()),
                        Optional.empty(),
                        Optional.empty(),
                        DivergenceKind.POSITION_MISMATCH,
                        quantity ? position.replayedQuantity() : position.replayedCostBasis(),
                        quantity ? position.quantity() : position.costBasis()));
            }
        }
        for (SummaryFacts summary : facts.summaries()) {
            boolean realized = summary.summaryRealized().compareTo(summary.directRealized()) != 0;
            if (realized || summary.summaryFees().compareTo(summary.directFees()) != 0) {
                divergences.add(new Divergence(
                        summary.botId(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(summary.utcDay()),
                        DivergenceKind.SUMMARY_MISMATCH,
                        realized ? summary.directRealized() : summary.directFees(),
                        realized ? summary.summaryRealized() : summary.summaryFees()));
            }
        }
        return divergences;
    }
}
