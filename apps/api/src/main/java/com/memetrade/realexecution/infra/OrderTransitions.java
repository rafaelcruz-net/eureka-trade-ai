package com.memetrade.realexecution.infra;

import com.memetrade.botwallet.api.RealExecutionWallet;
import com.memetrade.botwallet.api.RealWalletInfo;
import com.memetrade.realexecution.api.RealOrderState;
import com.memetrade.realexecution.domain.RealOrderStateMachine;
import com.memetrade.realexecution.infra.JdbcRealExecutionStore.OrderRow;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * One transition of an order, in the transaction of the caller: the compare-and-set on the expected state
 * (U4-NFR4.2), then, only when it won, the RESERVE_RELEASE of a buy that reached a final state (BR5.3) and the audit
 * event, the metric and the log line (BR10.1). The loser of a race does nothing more.
 */
@Component
public class OrderTransitions {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OrderTransitions.class);

    private final JdbcRealExecutionStore store;
    private final RealOrderLedger ledger;
    private final RealExecutionEvents events;
    private final RealExecutionWallet wallets;
    private final Clock clock;

    public OrderTransitions(
            JdbcRealExecutionStore store,
            RealOrderLedger ledger,
            RealExecutionEvents events,
            RealExecutionWallet wallets,
            Clock clock) {
        this.store = store;
        this.ledger = ledger;
        this.events = events;
        this.wallets = wallets;
        this.clock = clock;
    }

    /**
     * Moves {@code order} from its recorded state to {@code to} with the extra columns.
     *
     * @param owner the lease holder (the worker step), or empty for a change from outside (cancel, claim)
     * @param person the person who caused it, or empty for the worker
     * @return whether this writer won
     */
    // The compare-and-set, the release of the reserve and the audit event (which requires a transaction, C9) commit
    // together: a caller without a transaction (the driver before the send, the jobs) gets one here.
    @org.springframework.transaction.annotation.Transactional
    public boolean move(
            OrderRow order,
            RealOrderState to,
            String code,
            Optional<String> owner,
            Map<String, Object> extra,
            Optional<UUID> person,
            Duration lease) {
        RealOrderStateMachine.requireMove(order.state(), to);
        Instant now = clock.instant();
        Map<String, Object> columns = new LinkedHashMap<>(extra);
        columns.put("state", to.name());
        if (code != null) {
            columns.put("failure_code", code);
        }
        if (!store.update(order.realOrderId(), order.state(), owner, columns, lease, now)) {
            return false;
        }
        OrderRow moved = store.find(order.realOrderId()).orElseThrow();
        if (to.isFinal() && RealOrderStateMachine.releasesReserve(order.side(), to)) {
            // Review (Minor 5): the RESERVE_RELEASE goes to the wallet of the RESERVE, recorded on the order, so it
            // never depends on the wallet still being ACTIVE. A row without it (written before the review) falls back
            // to the active wallet; with neither, the move fails closed (rolled back, retried by the job) instead of
            // leaving the reserve held.
            UUID wallet = order.botWalletId() != null
                    ? order.botWalletId()
                    : botWalletOf(order).orElse(null);
            if (wallet == null) {
                log.error("REAL reserve release blocked, no bot wallet: order={}", order.realOrderId());
                throw new IllegalStateException("The reserve of order " + order.realOrderId() + " has no bot wallet");
            }
            ledger.releaseIfFinal(moved, wallet, to);
        }
        events.transition(moved, order.state(), to, code, person);
        return true;
    }

    /** Updates columns of the order without changing its state (the same compare-and-set). */
    public boolean record(OrderRow order, Optional<String> owner, Map<String, Object> columns, Duration lease) {
        return store.update(order.realOrderId(), order.state(), owner, columns, lease, clock.instant());
    }

    public Optional<UUID> botWalletOf(OrderRow order) {
        return wallets.wallet(order.tenantId(), order.userId()).map(RealWalletInfo::botWalletId);
    }

    static Timestamp at(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
