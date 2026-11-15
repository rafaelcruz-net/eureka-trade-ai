package com.memetrade.realexecution.infra;

import com.memetrade.botwallet.api.RealUpstreamUnavailable;
import com.memetrade.botwallet.api.SwapSigningRequest;
import com.memetrade.realexecution.api.RealFailureCode;
import com.memetrade.realexecution.api.RealOrderState;
import com.memetrade.realexecution.domain.RealOrderStateMachine;
import com.memetrade.realexecution.domain.RealOrderStateMachine.DeadlineVerdict;
import com.memetrade.realexecution.infra.JdbcRealExecutionStore.OrderRow;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * BR4.2 (S8, S25, S29): every second in the worker. The unclaimed sessions past their deadline of 8 s become CANCELLED
 * SIGNING_DEADLINE_EXPIRED with the reservation released, in one compare-and-set that never touches a claimed one. A
 * claim still QUOTED 15 s after it (the api died, or the TxSigner did not answer) is repeated with the SAME
 * signingRequestId and the transaction signed by the main wallet recorded at the claim; when the quote is older than
 * 10 s by then, the order fails QUOTE_EXPIRED and nothing is sent.
 */
@Component
public class SigningDeadlineJob {

    private static final Logger log = LoggerFactory.getLogger(SigningDeadlineJob.class);

    private final JdbcRealExecutionStore store;
    private final OrderTransitions transitions;
    private final RealOrderLedger ledger;
    private final RealExecutionEvents events;
    private final OrderDriver driver;
    private final RealExecutionMetrics metrics;
    private final RealExecutionProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final String owner = "signing-" + UUID.randomUUID();

    public SigningDeadlineJob(
            JdbcRealExecutionStore store,
            OrderTransitions transitions,
            RealOrderLedger ledger,
            RealExecutionEvents events,
            OrderDriver driver,
            RealExecutionMetrics metrics,
            RealExecutionConfig.CheckedSettings settings,
            Clock clock,
            PlatformTransactionManager manager) {
        this.store = store;
        this.transitions = transitions;
        this.ledger = ledger;
        this.events = events;
        this.driver = driver;
        this.metrics = metrics;
        this.properties = settings.properties();
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
    }

    public int runOnce() {
        Instant now = clock.instant();
        int handled = transactions.execute(status -> {
            List<OrderRow> expired = store.cancelExpiredSessions(now, properties.orderBatchSize());
            for (OrderRow order : expired) {
                transitions
                        .botWalletOf(order)
                        .ifPresent(wallet -> ledger.releaseIfFinal(order, wallet, RealOrderState.CANCELLED));
                events.transition(
                        order,
                        RealOrderState.QUOTED,
                        RealOrderState.CANCELLED,
                        RealFailureCode.SIGNING_DEADLINE_EXPIRED.name(),
                        Optional.empty());
            }
            return expired.size();
        });
        boolean failed = false;
        for (UUID orderId : store.claim(
                EnumSet.of(RealOrderState.QUOTED),
                "signing_mode = 'SIGNED' and signing_claimed_at is not null",
                owner,
                properties.lease(),
                properties.orderBatchSize())) {
            try {
                store.find(orderId).ifPresent(order -> stuck(order, now));
                handled++;
            } catch (RuntimeException failure) {
                failed = true;
                log.warn(
                        "REAL signing repetition stopped: order={}, error={}",
                        orderId,
                        failure.getClass().getSimpleName());
            } finally {
                store.releaseLease(orderId, owner);
            }
        }
        if (!failed) {
            metrics.jobRun(RealExecutionMetrics.Job.SIGNING_DEADLINE);
        }
        return handled;
    }

    private void stuck(OrderRow order, Instant now) {
        DeadlineVerdict verdict = RealOrderStateMachine.onSigningDeadline(
                now,
                order.signingDeadline(),
                Optional.ofNullable(order.signingClaimedAt()),
                order.quoteAt(),
                properties.claimStuckAfter(),
                properties.quoteMaxAge());
        switch (verdict) {
            case RETRY_SIGNING -> {
                try {
                    driver.askSignatureOrThrow(
                            order, order.signedTransaction(), SwapSigningRequest.Mode.SIGNED, Optional.of(owner));
                } catch (RealUpstreamUnavailable down) {
                    log.warn("REAL signing repetition: the TxSigner did not answer: order={}", order.realOrderId());
                }
            }
            case FAIL_QUOTE_EXPIRED ->
                transactions.executeWithoutResult(status -> transitions.move(
                        order,
                        RealOrderState.FAILED,
                        RealFailureCode.QUOTE_EXPIRED.name(),
                        Optional.empty(),
                        Map.of(),
                        Optional.empty(),
                        properties.lease()));
            default -> {
                // Claimed less than 15 s ago: the api is still on it.
            }
        }
    }
}
