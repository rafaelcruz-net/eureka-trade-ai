package com.memetrade.realexecution.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.memetrade.botwallet.api.RealExecutionWallet;
import com.memetrade.botwallet.api.RealWalletInfo;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.ledger.api.RealPosition;
import com.memetrade.realexecution.api.PositionExitGuard;
import com.memetrade.realexecution.api.RealFailureCode;
import com.memetrade.realexecution.api.RealOrderState;
import com.memetrade.realexecution.api.RealSide;
import com.memetrade.realexecution.domain.QuoteMath;
import com.memetrade.realexecution.infra.JdbcRealExecutionStore.LiquidationRow;
import com.memetrade.realexecution.infra.JdbcRealExecutionStore.OrderRow;
import com.memetrade.riskcontrol.api.GateDecision;
import com.memetrade.riskcontrol.api.OrderIntent;
import com.memetrade.riskcontrol.api.RealRiskGate;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * F8, BR8.1, BR8.2: every second in the worker. A SELLING liquidation waits while the person has an order in PROPOSED,
 * QUOTED, SIGNED or SENT (the orders sent before the request end first, S26); then creates ONE sale of the next open
 * position (the quantity read from M2 at that moment), evaluated by the barrier (M5, origin USER_LIQUIDATION),
 * idempotent by {@code <liquidationId>:<mint>}. A position whose sale failed or is AMBIGUOUS is not sold again. When no
 * sale is in flight and no position is left to sell it asks the withdrawal of everything free (M1, key = liquidationId,
 * never waiting for an ambiguous order) and ends DONE when every sale confirmed, otherwise PARTIAL with the positions
 * that stayed and why.
 */
@Component
public class LiquidationJob {

    private static final Logger log = LoggerFactory.getLogger(LiquidationJob.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcRealExecutionStore store;
    private final RealExecutionWallet wallets;
    private final RealLedger ledger;
    private final RealRiskGate gate;
    private final RealExecutionEvents events;
    private final RealExecutionMetrics metrics;
    private final RealExecutionProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final PositionExitGuard exits;

    public LiquidationJob(
            JdbcRealExecutionStore store,
            RealExecutionWallet wallets,
            RealLedger ledger,
            RealRiskGate gate,
            RealExecutionEvents events,
            RealExecutionMetrics metrics,
            RealExecutionConfig.CheckedSettings settings,
            Clock clock,
            PlatformTransactionManager manager,
            PositionExitGuard exits) {
        this.exits = exits;
        this.store = store;
        this.wallets = wallets;
        this.ledger = ledger;
        this.gate = gate;
        this.events = events;
        this.metrics = metrics;
        this.properties = settings.properties();
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
    }

    public int runOnce() {
        boolean failed = false;
        int handled = 0;
        for (LiquidationRow liquidation : store.liquidationsInProgress(properties.orderBatchSize())) {
            try {
                if ("SELLING".equals(liquidation.state())) {
                    sell(liquidation);
                } else {
                    withdraw(liquidation);
                }
                handled++;
            } catch (RuntimeException failure) {
                failed = true;
                log.warn(
                        "REAL liquidation step stopped: liquidation={}, error={}",
                        liquidation.liquidationId(),
                        failure.getClass().getSimpleName());
            }
        }
        if (!failed) {
            metrics.jobRun(RealExecutionMetrics.Job.LIQUIDATION);
        }
        return handled;
    }

    private void sell(LiquidationRow liquidation) {
        if (store.hasOrderInFlight(liquidation.tenantId(), liquidation.userId())) {
            return;
        }
        Optional<RealWalletInfo> wallet = wallets.wallet(liquidation.tenantId(), liquidation.userId());
        if (wallet.isEmpty()) {
            return;
        }
        Set<String> skip = new HashSet<>(store.ambiguousMints(liquidation.tenantId(), liquidation.userId()));
        store.salesOf(liquidation.liquidationId()).forEach(sale -> skip.add(sale.mint()));
        List<RealPosition> candidates = ledger
                .positions(liquidation.tenantId(), wallet.get().botWalletId())
                .stream()
                .filter(position -> !skip.contains(position.mint()))
                .toList();
        boolean waiting = false;
        for (RealPosition candidate : candidates) {
            Boolean created = transactions.execute(status -> saleOf(liquidation, wallet.get(), candidate));
            if (Boolean.TRUE.equals(created)) {
                return;
            }
            waiting = waiting || created == null;
        }
        if (waiting) {
            // 261113-trading-real-bot U7: a position still held by a conditional waits for the next round.
            return;
        }
        if (store.moveLiquidation(liquidation.liquidationId(), "SELLING", "WITHDRAWING", null, "[]", clock.instant())) {
            withdraw(new LiquidationRow(
                    liquidation.liquidationId(), liquidation.tenantId(), liquidation.userId(), "WITHDRAWING", null));
        }
    }

    /**
     * 261113-trading-real-bot U7 (BR4.3, BR6.8, logical-components.md "Liquidação (U4)"): under the person lock, the
     * guard of the conditional exits answers first; WAIT leaves the position for the next round (the other positions of
     * the round go on), READY reads the position again from the REAL ledger and creates its sale.
     *
     * @return TRUE when a sale was created, FALSE when nothing is left of the position, null when it waits
     */
    private Boolean saleOf(LiquidationRow liquidation, RealWalletInfo wallet, RealPosition candidate) {
        try {
            return gate.withPersonLock(liquidation.tenantId(), liquidation.userId(), () -> {
                if (exits.releaseForSale(liquidation.tenantId(), liquidation.userId(), candidate.mint())
                        == PositionExitGuard.ExitRelease.WAIT) {
                    return null;
                }
                Optional<RealPosition> fresh = ledger.positions(liquidation.tenantId(), wallet.botWalletId()).stream()
                        .filter(position -> position.mint().equals(candidate.mint()))
                        .findFirst();
                if (fresh.isEmpty()) {
                    return Boolean.FALSE;
                }
                createSale(liquidation, wallet, fresh.get());
                return Boolean.TRUE;
            });
        } catch (com.memetrade.riskcontrol.api.RealGateUnavailable noLock) {
            return null;
        }
    }

    private void createSale(LiquidationRow liquidation, RealWalletInfo wallet, RealPosition position) {
        Instant now = clock.instant();
        String mode = wallet.mode().name();
        JdbcRealExecutionStore.NewOrder sale = new JdbcRealExecutionStore.NewOrder(
                UUID.randomUUID(),
                liquidation.tenantId(),
                liquidation.userId(),
                null,
                liquidation.liquidationId(),
                null,
                "USER_LIQUIDATION",
                liquidation.liquidationId() + ":" + position.mint(),
                RealSide.SELL,
                position.mint(),
                null,
                position.tokenBaseUnits(),
                mode,
                DecisionConsumer.signingModeOf(mode),
                QuoteMath.slippageOf(wallet.maxSlippageBps()),
                "Vender tudo e sacar",
                now,
                wallet.botWalletId());
        GateDecision decision = gate.evaluate(OrderIntent.userLiquidation(
                liquidation.tenantId(), liquidation.userId(), position.mint(), position.tokenBaseUnits()));
        if (decision.approved()) {
            store.insertOrder(sale);
            events.transition(
                    store.find(sale.realOrderId()).orElseThrow(),
                    null,
                    RealOrderState.PROPOSED,
                    null,
                    Optional.empty());
        } else {
            store.insertRefusedSale(sale, RealFailureCode.RISK_REJECTED.name());
        }
    }

    private void withdraw(LiquidationRow liquidation) {
        Optional<UUID> withdrawal = liquidation.withdrawalId() != null
                ? Optional.of(liquidation.withdrawalId())
                : wallets.requestWithdrawalAll(
                        liquidation.tenantId(),
                        liquidation.userId(),
                        liquidation.liquidationId().toString());
        List<OrderRow> sales = store.salesOf(liquidation.liquidationId());
        Set<String> ambiguous = new HashSet<>(store.ambiguousMints(liquidation.tenantId(), liquidation.userId()));
        ArrayNode remaining = JSON.createArrayNode();
        Optional<RealWalletInfo> wallet = wallets.wallet(liquidation.tenantId(), liquidation.userId());
        List<RealPosition> left = wallet.isEmpty()
                ? List.of()
                : ledger.positions(liquidation.tenantId(), wallet.get().botWalletId());
        for (RealPosition position : left) {
            var item = remaining.addObject();
            item.put("mint", position.mint());
            item.put("baseUnits", Long.toString(position.tokenBaseUnits()));
            item.put("reason", reasonOf(position.mint(), sales, ambiguous));
        }
        boolean done = remaining.isEmpty() && sales.stream().allMatch(s -> s.state() == RealOrderState.CONFIRMED);
        store.moveLiquidation(
                liquidation.liquidationId(),
                "WITHDRAWING",
                done ? "DONE" : "PARTIAL",
                withdrawal.orElse(null),
                remaining.toString(),
                clock.instant());
    }

    /** The code of the failed sale of the mint, "AMBIGUOUS" for a sale still in conference, or "NOT_SOLD". */
    private static String reasonOf(String mint, List<OrderRow> sales, Set<String> ambiguous) {
        if (ambiguous.contains(mint)) {
            return "AMBIGUOUS";
        }
        return sales.stream()
                .filter(sale -> sale.mint().equals(mint) && sale.failureCode() != null)
                .map(OrderRow::failureCode)
                .reduce((first, second) -> second)
                .orElse("NOT_SOLD");
    }
}
