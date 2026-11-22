package com.memetrade.realexecution.infra;

import com.memetrade.realexecution.api.CancelScope;
import com.memetrade.realexecution.api.RealDecision;
import com.memetrade.realexecution.api.RealExecutionPort;
import com.memetrade.realexecution.api.RealFailureCode;
import com.memetrade.realexecution.api.RealOrderState;
import com.memetrade.realexecution.api.RealOrderView;
import com.memetrade.realexecution.domain.RealOrderStateMachine;
import com.memetrade.realexecution.infra.JdbcRealExecutionStore.OrderRow;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** M6 (contract-summary.md, owner U4): the decision queue, the cancellation of the unsent orders and the reads. */
@Service
public class RealExecutionService implements RealExecutionPort {

    private final JdbcRealExecutionStore store;
    private final RealOrderLedger ledger;
    private final RealExecutionEvents events;
    private final OrderTransitions transitions;
    private final Clock clock;

    public RealExecutionService(
            JdbcRealExecutionStore store,
            RealOrderLedger ledger,
            RealExecutionEvents events,
            OrderTransitions transitions,
            Clock clock) {
        this.store = store;
        this.ledger = ledger;
        this.events = events;
        this.transitions = transitions;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID recordDecision(RealDecision decision) {
        return store.insertDecision(new JdbcRealExecutionStore.DecisionRow(
                UUID.randomUUID(),
                decision.tenantId(),
                decision.userId(),
                decision.botId(),
                decision.decisionId(),
                decision.approvalId(),
                decision.side(),
                decision.mint(),
                decision.inputLamports(),
                decision.tokenBaseUnits(),
                decision.reason(),
                clock.instant()));
    }

    @Override
    public boolean hasOrderInProgress(UUID tenantId, UUID botId) {
        return store.hasOrderInProgress(tenantId, botId);
    }

    @Override
    public boolean liquidationActive(UUID tenantId, UUID userId) {
        return store.liquidationActive(tenantId, userId);
    }

    /** F10: PROPOSED and QUOTED become CANCELLED with the reservation released, in one transaction. */
    @Override
    @Transactional
    public int cancelUnsent(CancelScope scope, RealFailureCode reason) {
        List<OrderRow> cancelled = store.cancel(scope, RealOrderStateMachine.CANCEL_UNSENT, reason, clock.instant());
        for (OrderRow order : cancelled) {
            transitions
                    .botWalletOf(order)
                    .ifPresent(wallet -> ledger.releaseIfFinal(order, wallet, RealOrderState.CANCELLED));
            events.transition(order, null, RealOrderState.CANCELLED, reason.name(), Optional.empty());
        }
        return cancelled.size();
    }

    @Override
    public java.util.Set<String> knownTransactions(java.util.Collection<String> signatures) {
        return store.knownSignatures(signatures);
    }

    @Override
    public int unsentOrders() {
        return store.countUnsent();
    }

    @Override
    public int ordersInTransit(UUID tenantId, UUID userId) {
        return store.countInTransit(tenantId, userId);
    }

    @Override
    public List<RealOrderView> recentOrders(UUID tenantId, UUID userId, int limit) {
        int bounded = Math.max(1, Math.min(50, limit));
        return store
                .listForOwner(
                        tenantId,
                        userId,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        bounded)
                .stream()
                .map(RealExecutionService::view)
                .toList();
    }

    @Override
    public List<RealOrderView> settledOrders(UUID tenantId, UUID userId) {
        return store.settled(tenantId, userId).stream()
                .map(RealExecutionService::view)
                .toList();
    }

    @Override
    public List<RealOrderView> settledOrders(UUID tenantId, UUID userId, int max) {
        return store.settled(tenantId, userId, max).stream()
                .map(RealExecutionService::view)
                .toList();
    }

    @Override
    public long changeMark(UUID tenantId, UUID userId) {
        return store.markFor(tenantId, userId);
    }

    @Override
    public List<RealOrderView> changedSince(UUID tenantId, UUID userId, java.time.Instant since, int limit) {
        return store.changedSince(tenantId, userId, since, Math.max(1, Math.min(100, limit))).stream()
                .map(RealExecutionService::view)
                .toList();
    }

    /** The view of an order for the routes R3. */
    public static RealOrderView viewOf(OrderRow row) {
        return view(row);
    }

    static RealOrderView view(OrderRow row) {
        return new RealOrderView(
                row.realOrderId(),
                Optional.ofNullable(row.botId()),
                Optional.ofNullable(row.liquidationId()),
                row.origin(),
                row.side(),
                row.mint(),
                Optional.ofNullable(row.inputLamports()),
                Optional.ofNullable(row.inputBaseUnits()),
                Optional.ofNullable(row.expectedOut()),
                Optional.ofNullable(row.minOut()),
                Optional.ofNullable(row.outBaseUnits()),
                row.operationMode(),
                row.state(),
                Optional.ofNullable(row.failureCode()).map(RealFailureCode::valueOf),
                Optional.ofNullable(row.txSignature()),
                Optional.ofNullable(row.feeLamports()),
                Optional.ofNullable(row.feePayer()),
                Optional.ofNullable(row.signingDeadline()),
                row.createdAt(),
                row.updatedAt(),
                Optional.ofNullable(row.rentLamports()));
    }
}
