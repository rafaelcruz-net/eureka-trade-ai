package com.memetrade.realexecution.infra;

import com.memetrade.botwallet.api.RealChain;
import com.memetrade.botwallet.api.RealUpstreamUnavailable;
import com.memetrade.botwallet.api.RealWalletInfo;
import com.memetrade.realexecution.api.RealOrderState;
import com.memetrade.realexecution.domain.RealOrderStateMachine;
import com.memetrade.realexecution.domain.RealOrderStateMachine.ChainStatus;
import com.memetrade.realexecution.domain.RealOrderStateMachine.NetworkVerdict;
import com.memetrade.realexecution.infra.JdbcRealExecutionStore.OrderRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * F5, BR5.1 (U4-NFR4.4, U4-NFR3.5): every 2 s in the worker, takes the lease of the SENT orders and asks the network.
 * Finalized is CONFIRMED with the values of the network; rejected is FAILED; absent with a valid blockhash is RESENT
 * with the SAME recorded transaction (a send whose intent was recorded and whose call never happened, S24); absent past
 * the blockhash is FAILED BLOCKHASH_EXPIRED; 60 s after the send without a final answer it becomes AMBIGUOUS. It never
 * signs nor builds anything.
 */
@Component
public class ConfirmationJob {

    private static final Logger log = LoggerFactory.getLogger(ConfirmationJob.class);

    private final JdbcRealExecutionStore store;
    private final OrderSettlement settlement;
    private final OrderDriver driver;
    private final RealChain chain;
    private final RealExecutionMetrics metrics;
    private final RealExecutionProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final String owner = "confirmation-" + UUID.randomUUID();

    public ConfirmationJob(
            JdbcRealExecutionStore store,
            OrderSettlement settlement,
            OrderDriver driver,
            RealChain chain,
            RealExecutionMetrics metrics,
            RealExecutionConfig.CheckedSettings settings,
            Clock clock,
            PlatformTransactionManager manager) {
        this.store = store;
        this.settlement = settlement;
        this.driver = driver;
        this.chain = chain;
        this.metrics = metrics;
        this.properties = settings.properties();
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
    }

    public int runOnce() {
        List<UUID> claimed = store.claim(
                EnumSet.of(RealOrderState.SENT), "", owner, properties.lease(), properties.orderBatchSize());
        boolean failed = false;
        for (UUID orderId : claimed) {
            try {
                store.find(orderId).ifPresent(this::confirm);
            } catch (RuntimeException failure) {
                failed = true;
                log.warn(
                        "REAL confirmation stopped: order={}, error={}",
                        orderId,
                        failure.getClass().getSimpleName());
            } finally {
                store.releaseLease(orderId, owner);
            }
        }
        if (!failed) {
            metrics.jobRun(RealExecutionMetrics.Job.CONFIRMATION);
        }
        return claimed.size();
    }

    private void confirm(OrderRow order) {
        Instant now = clock.instant();
        ChainStatus status;
        long height = 0;
        try {
            status = OrderSettlement.statusOf(chain.status(order.txSignature()));
            height = chain.blockHeight();
        } catch (RealUpstreamUnavailable down) {
            metrics.upstreamFailure(RealExecutionMetrics.Provider.RPC);
            status = ChainStatus.UNAVAILABLE;
        }
        Duration sinceSend = Duration.between(order.sentAt(), now);
        NetworkVerdict verdict = RealOrderStateMachine.onNetwork(
                RealOrderState.SENT,
                status,
                sinceSend,
                height,
                order.lastValidBlockHeight(),
                properties.confirmationTimeout());
        switch (verdict.kind()) {
            case MOVE -> {
                if (settlement.settle(order, verdict)) {
                    metrics.sendToFinal(sinceSend.abs());
                }
            }
            case RESEND -> {
                UUID attempt =
                        transactions.execute(s -> store.insertAttempt(order.realOrderId(), order.tenantId(), now));
                driver.sendRecorded(order, attempt);
            }
            case STAY -> {
                // Still pending on the network: the next round asks again.
            }
        }
    }

    /** The wallet of the order (for its address and the ledger). */
    static RealWalletInfo walletOf(com.memetrade.botwallet.api.RealExecutionWallet wallets, OrderRow order) {
        return wallets.wallet(order.tenantId(), order.userId()).orElseThrow();
    }
}
