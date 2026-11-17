package com.memetrade.realexecution.infra;

import com.memetrade.bots.api.RealBotOwnership;
import com.memetrade.botwallet.api.RealExecutionWallet;
import com.memetrade.botwallet.api.RealWalletInfo;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.realexecution.api.PositionExitGuard;
import com.memetrade.realexecution.api.RealSide;
import com.memetrade.realexecution.domain.QuoteMath;
import com.memetrade.realexecution.infra.JdbcRealExecutionStore.DecisionRow;
import com.memetrade.riskcontrol.api.HandOverResult;
import com.memetrade.riskcontrol.api.RealGateUnavailable;
import com.memetrade.riskcontrol.api.RealRiskGate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * F2, BR2.1 (logical-components.md): every 200 ms in the worker, each PENDING decision becomes a PROPOSED order in ONE
 * short transaction, without any call to the network. The row of the decision is locked with {@code SKIP LOCKED} and
 * the person with the lock of the U3 ({@link RealRiskGate#withPersonLock}), the same the liquidation takes, so the two
 * never run together for one person (S30). Without the lock the decision stays PENDING for the next round.
 *
 * <p>Inside: the REAL flag, the ownership of the bot, an order in progress of the bot, an active liquidation of the
 * person; for a buy the hand-over of the reservation of the barrier and then the RESERVE of the ledger; the order with
 * the current mode of the wallet; the decision PROCESSED. When the partial unique index of BR2.2 is violated anyway,
 * the transaction rolls back (no RESERVE stays) and the decision is DISCARDED in a new one.
 *
 * <p>261113-trading-real-bot U7 (BR4.3): a sale asks the {@link PositionExitGuard} first; a position whose conditional
 * is being cancelled defers the decision ({@code not_before}, {@code attempts}) and a position at zero discards it with
 * NOTHING_TO_SELL, so a position is never sold twice.
 */
@Component
public class DecisionConsumer {

    private static final Logger log = LoggerFactory.getLogger(DecisionConsumer.class);

    /** 261113-trading-real-bot U7: a deferred sale is read again 30 s later (logical-components.md). */
    static final Duration EXIT_RETRY = Duration.ofSeconds(30);

    /** U7: after 10 attempts (about 5 minutes) the sale is discarded with EXIT_CANCEL_TIMEOUT (alert U7-NFR5.7). */
    static final int MAX_EXIT_ATTEMPTS = 10;

    private final JdbcRealExecutionStore store;
    private final RealRiskGate gate;
    private final RealBotOwnership bots;
    private final RealExecutionWallet wallets;
    private final RealOrderLedger ledger;
    private final RealExecutionEvents events;
    private final RealExecutionMetrics metrics;
    private final RealExecutionConfig.CheckedSettings settings;
    private final Environment environment;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final PositionExitGuard exits;
    private final RealLedger realLedger;

    public DecisionConsumer(
            JdbcRealExecutionStore store,
            RealRiskGate gate,
            RealBotOwnership bots,
            RealExecutionWallet wallets,
            RealOrderLedger ledger,
            RealExecutionEvents events,
            RealExecutionMetrics metrics,
            RealExecutionConfig.CheckedSettings settings,
            Environment environment,
            Clock clock,
            PlatformTransactionManager manager,
            PositionExitGuard exits,
            RealLedger realLedger) {
        this.exits = exits;
        this.realLedger = realLedger;
        this.store = store;
        this.gate = gate;
        this.bots = bots;
        this.wallets = wallets;
        this.ledger = ledger;
        this.events = events;
        this.metrics = metrics;
        this.settings = settings;
        this.environment = environment;
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
    }

    /** One round: returns how many decisions were closed (PROCESSED or DISCARDED). */
    public int runOnce() {
        int closed = 0;
        boolean failed = false;
        for (UUID requestId : store.pendingDecisions(settings.properties().decisionBatchSize(), clock.instant())) {
            try {
                if (consume(requestId)) {
                    closed++;
                }
            } catch (RealGateUnavailable noLock) {
                log.debug(
                        "REAL decision left PENDING: dependency={}",
                        noLock.dependency().label());
            } catch (RuntimeException failure) {
                failed = true;
                log.warn(
                        "REAL decision not consumed: request={}, error={}",
                        requestId,
                        failure.getClass().getSimpleName());
            }
        }
        if (!failed) {
            metrics.jobRun(RealExecutionMetrics.Job.QUEUE);
        }
        return closed;
    }

    private boolean consume(UUID requestId) {
        try {
            return Boolean.TRUE.equals(transactions.execute(status -> store.lockPendingDecision(requestId)
                    .map(decision ->
                            gate.withPersonLock(decision.tenantId(), decision.userId(), () -> process(decision)))
                    .orElse(false)));
        } catch (DuplicateKeyException orderInProgress) {
            // BR2.2: the partial unique index won the race; nothing of the rolled back transaction stays.
            transactions.executeWithoutResult(
                    status -> store.closeDecision(requestId, "DISCARDED", "ORDER_IN_PROGRESS", clock.instant()));
            return true;
        }
    }

    private boolean process(DecisionRow decision) {
        Instant now = clock.instant();
        store.decisionCreatedAt(decision.decisionRequestId())
                .ifPresent(created ->
                        metrics.queueWait(Duration.between(created, now).abs()));
        Optional<String> discard = discardReason(decision);
        if (discard.isPresent()) {
            store.closeDecision(decision.decisionRequestId(), "DISCARDED", discard.get(), now);
            return true;
        }
        RealWalletInfo wallet =
                wallets.wallet(decision.tenantId(), decision.userId()).orElseThrow();
        Long sellUnits = decision.tokenBaseUnits();
        if (decision.side() == RealSide.SELL) {
            Optional<Long> position = releasedPosition(decision, wallet, now);
            if (position.isEmpty()) {
                return false;
            }
            if (position.get() <= 0) {
                store.closeDecision(decision.decisionRequestId(), "DISCARDED", "NOTHING_TO_SELL", now);
                return true;
            }
            sellUnits = Math.min(decision.tokenBaseUnits(), position.get());
        }
        if (decision.side() == RealSide.BUY) {
            HandOverResult handOver =
                    gate.handOver(decision.tenantId(), decision.approvalId().orElseThrow());
            if (!handOver.mayCreateOrder()) {
                store.closeDecision(decision.decisionRequestId(), "DISCARDED", "RESERVATION_EXPIRED", now);
                return true;
            }
        }
        UUID orderId = UUID.randomUUID();
        String mode = wallet.mode().name();
        store.insertOrder(new JdbcRealExecutionStore.NewOrder(
                orderId,
                decision.tenantId(),
                decision.userId(),
                decision.botId(),
                null,
                decision.decisionRequestId(),
                "BOT",
                decision.decisionRequestId().toString(),
                decision.side(),
                decision.mint(),
                decision.inputLamports(),
                sellUnits,
                mode,
                signingModeOf(mode),
                QuoteMath.slippageOf(wallet.maxSlippageBps()),
                decision.reason(),
                now,
                wallet.botWalletId()));
        if (decision.side() == RealSide.BUY) {
            ledger.reserve(decision.tenantId(), wallet.botWalletId(), orderId, decision.inputLamports());
        }
        store.closeDecision(decision.decisionRequestId(), "PROCESSED", null, now);
        events.transition(
                store.find(orderId).orElseThrow(),
                null,
                com.memetrade.realexecution.api.RealOrderState.PROPOSED,
                null,
                Optional.empty());
        return true;
    }

    /**
     * 261113-trading-real-bot U7 (BR4.3, logical-components.md "DEFERRED"): before a sale the guard of the conditional
     * exits answers, in this transaction and under this person lock, without the network. WAIT defers the decision 30 s
     * (attempts + 1) and, at the tenth attempt, discards it with EXIT_CANCEL_TIMEOUT; READY reads the position again from
     * the REAL ledger (a conditional may have sold it).
     *
     * @return the position of the mint now, or empty when the decision was deferred or discarded here
     */
    private Optional<Long> releasedPosition(DecisionRow decision, RealWalletInfo wallet, Instant now) {
        if (exits.releaseForSale(decision.tenantId(), decision.userId(), decision.mint())
                == PositionExitGuard.ExitRelease.WAIT) {
            if (store.decisionAttempts(decision.decisionRequestId()) + 1 >= MAX_EXIT_ATTEMPTS) {
                store.closeDecision(decision.decisionRequestId(), "DISCARDED", "EXIT_CANCEL_TIMEOUT", now);
                log.warn("REAL sale discarded: the conditional of the position was not cancelled in time");
            } else {
                store.deferDecision(decision.decisionRequestId(), now.plus(EXIT_RETRY));
            }
            return Optional.empty();
        }
        return Optional.of(realLedger.positions(decision.tenantId(), wallet.botWalletId()).stream()
                .filter(held -> held.mint().equals(decision.mint()))
                .mapToLong(com.memetrade.ledger.api.RealPosition::tokenBaseUnits)
                .sum());
    }

    private Optional<String> discardReason(DecisionRow decision) {
        if (!environment.getProperty("memetrade.real.enabled", Boolean.class, false)) {
            return Optional.of("REAL_DISABLED");
        }
        if (!bots.activeRealBotOf(decision.tenantId(), decision.userId(), decision.botId())) {
            return Optional.of("BOT_NOT_OWNED");
        }
        if (wallets.wallet(decision.tenantId(), decision.userId()).isEmpty()) {
            return Optional.of("STATE_CHANGED");
        }
        if (store.hasOrderInProgress(decision.tenantId(), decision.botId())) {
            return Optional.of("ORDER_IN_PROGRESS");
        }
        if (store.liquidationActive(decision.tenantId(), decision.userId())) {
            return Optional.of("LIQUIDATION_ACTIVE");
        }
        return Optional.empty();
    }

    /**
     * BR4.1: only AUTO_FULL signs through the TxSigner alone; the market orders of SIGNED and AUTO_EXITS are co-signed by
     * the main wallet.
     */
    static String signingModeOf(String operationMode) {
        return "AUTO_FULL".equals(operationMode) ? "AUTO_FULL" : "SIGNED";
    }
}
