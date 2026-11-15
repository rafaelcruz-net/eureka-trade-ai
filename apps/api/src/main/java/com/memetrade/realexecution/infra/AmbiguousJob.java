package com.memetrade.realexecution.infra;

import com.memetrade.botwallet.api.RealChain;
import com.memetrade.botwallet.api.RealPauseReason;
import com.memetrade.botwallet.api.RealPauses;
import com.memetrade.botwallet.api.RealUpstreamUnavailable;
import com.memetrade.realexecution.api.RealOrderState;
import com.memetrade.realexecution.domain.RealOrderStateMachine;
import com.memetrade.realexecution.domain.RealOrderStateMachine.ChainStatus;
import com.memetrade.realexecution.domain.RealOrderStateMachine.NetworkVerdict;
import com.memetrade.realexecution.infra.JdbcRealExecutionStore.OrderRow;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * F6, BR5.2 (S12 to S14; U4-NFR4.5): every 15 s in the worker, ONLY asks the network about the AMBIGUOUS orders. There
 * is no resend nor re-signature of an ambiguous order anywhere in the code. Finalized is CONFIRMED; absent past its
 * blockhash is FAILED BLOCKHASH_EXPIRED; anything else keeps it AMBIGUOUS with its reservation. With the RPC down the
 * REAL of the person pauses (UPSTREAM) and the alert of the oldest ambiguous order fires. The same round refreshes the
 * gauges of the ambiguous and of the oldest SENT order (U4-NFR5.4, U4-NFR3.5).
 */
@Component
public class AmbiguousJob {

    private static final Logger log = LoggerFactory.getLogger(AmbiguousJob.class);

    private final JdbcRealExecutionStore store;
    private final OrderSettlement settlement;
    private final RealChain chain;
    private final RealPauses pauses;
    private final RealExecutionMetrics metrics;
    private final RealExecutionProperties properties;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final String owner = "ambiguous-" + UUID.randomUUID();

    public AmbiguousJob(
            JdbcRealExecutionStore store,
            OrderSettlement settlement,
            RealChain chain,
            RealPauses pauses,
            RealExecutionMetrics metrics,
            RealExecutionConfig.CheckedSettings settings,
            JdbcClient jdbc,
            Clock clock,
            PlatformTransactionManager manager) {
        this.store = store;
        this.settlement = settlement;
        this.chain = chain;
        this.pauses = pauses;
        this.metrics = metrics;
        this.properties = settings.properties();
        this.jdbc = jdbc;
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
    }

    public int runOnce() {
        boolean failed = false;
        int handled = 0;
        for (UUID orderId : store.claim(
                EnumSet.of(RealOrderState.AMBIGUOUS), "", owner, properties.lease(), properties.orderBatchSize())) {
            try {
                store.find(orderId).ifPresent(this::conference);
                handled++;
            } catch (RuntimeException failure) {
                failed = true;
                log.warn(
                        "REAL ambiguous conference stopped: order={}, error={}",
                        orderId,
                        failure.getClass().getSimpleName());
            } finally {
                store.releaseLease(orderId, owner);
            }
        }
        refreshGauges();
        if (!failed) {
            metrics.jobRun(RealExecutionMetrics.Job.AMBIGUOUS);
        }
        return handled;
    }

    private void conference(OrderRow order) {
        ChainStatus status;
        long height;
        try {
            status = OrderSettlement.statusOf(chain.status(order.txSignature()));
            height = chain.blockHeight();
        } catch (RealUpstreamUnavailable down) {
            metrics.upstreamFailure(RealExecutionMetrics.Provider.RPC);
            transactions.executeWithoutResult(status2 -> {
                if (pauses.pauseUser(order.tenantId(), order.userId(), RealPauseReason.UPSTREAM)
                        == RealPauses.PauseOutcome.PAUSED) {
                    metrics.upstreamPause();
                }
            });
            log.warn("REAL ambiguous order without the RPC: order={}", order.realOrderId());
            return;
        }
        NetworkVerdict verdict = RealOrderStateMachine.onNetwork(
                RealOrderState.AMBIGUOUS,
                status,
                Duration.between(order.sentAt(), clock.instant()),
                height,
                order.lastValidBlockHeight(),
                properties.confirmationTimeout());
        if (verdict.kind() == NetworkVerdict.Kind.MOVE) {
            settlement.settle(order, verdict);
        }
    }

    private void refreshGauges() {
        Instant now = clock.instant();
        var row = jdbc.sql("""
                        select
                          (select count(*) from real_order where state = 'AMBIGUOUS') as ambiguous,
                          (select min(updated_at) from real_order where state = 'AMBIGUOUS') as oldest_ambiguous,
                          (select min(sent_at) from real_order where state = 'SENT') as oldest_sent""").query().singleRow();
        metrics.gauges(
                ((Number) row.get("ambiguous")).longValue(),
                age(row.get("oldest_ambiguous"), now),
                age(row.get("oldest_sent"), now));
    }

    private static long age(Object timestamp, Instant now) {
        if (!(timestamp instanceof Timestamp at)) {
            return 0;
        }
        return Math.max(0, Duration.between(at.toInstant(), now).toSeconds());
    }
}
