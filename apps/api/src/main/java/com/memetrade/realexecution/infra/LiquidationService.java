package com.memetrade.realexecution.infra;

import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.botwallet.api.RealExecutionWallet;
import com.memetrade.identity.api.Principal;
import com.memetrade.realexecution.api.CancelScope;
import com.memetrade.realexecution.api.RealExecutionPort;
import com.memetrade.realexecution.api.RealFailureCode;
import com.memetrade.riskcontrol.api.RealGateUnavailable;
import com.memetrade.riskcontrol.api.RealRiskGate;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.web.CorrelationId;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * F8, BR8.1 ({@code POST /api/v1/real/liquidations}): under the lock of the person of the U3 (the same the consumer of
 * the queue takes before the hand-over, so the two never run together for one person, S30), refuses a liquidation in
 * progress (ORDER_ALREADY_IN_PROGRESS) and an AMBIGUOUS order (ORDER_AMBIGUOUS), creates the liquidation SELLING and
 * cancels the orders not sent of the person (LIQUIDATION), in ONE transaction. The sales and the withdrawal follow in the
 * worker ({@link LiquidationJob}).
 */
@Service
public class LiquidationService {

    private final JdbcRealExecutionStore store;
    private final RealRiskGate gate;
    private final RealExecutionPort port;
    private final RealExecutionWallet wallets;
    private final AuditEventPublisher audit;
    private final Environment environment;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public LiquidationService(
            JdbcRealExecutionStore store,
            RealRiskGate gate,
            RealExecutionPort port,
            RealExecutionWallet wallets,
            AuditEventPublisher audit,
            Environment environment,
            Clock clock,
            PlatformTransactionManager manager) {
        this.store = store;
        this.gate = gate;
        this.port = port;
        this.wallets = wallets;
        this.audit = audit;
        this.environment = environment;
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
    }

    public UUID start(Principal person) {
        if (!environment.getProperty("memetrade.real.enabled", Boolean.class, false)) {
            throw new ApiException(ErrorCode.REAL_DISABLED, "REAL is disabled in this environment.");
        }
        if (wallets.wallet(person.tenantId(), person.userId()).isEmpty()) {
            throw new ApiException(ErrorCode.BOT_WALLET_NOT_ACTIVE, "No active bot wallet.");
        }
        try {
            return transactions.execute(
                    status -> gate.withPersonLock(person.tenantId(), person.userId(), () -> create(person)));
        } catch (RealGateUnavailable noLock) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Try again in a moment.");
        }
    }

    private UUID create(Principal person) {
        if (store.liquidationActive(person.tenantId(), person.userId())) {
            throw new ApiException(ErrorCode.ORDER_ALREADY_IN_PROGRESS, "A liquidation is already in progress.");
        }
        if (store.hasAmbiguous(person.tenantId(), person.userId())) {
            throw new ApiException(ErrorCode.ORDER_AMBIGUOUS, "An ambiguous order is still being checked.");
        }
        Instant now = clock.instant();
        UUID liquidationId = UUID.randomUUID();
        store.insertLiquidation(liquidationId, person.tenantId(), person.userId(), now);
        int cancelled =
                port.cancelUnsent(CancelScope.user(person.tenantId(), person.userId()), RealFailureCode.LIQUIDATION);
        audit.publish(AuditEventCommand.person(
                person.tenantId(),
                person.userId(),
                "REAL_LIQUIDATION_REQUESTED",
                "real-liquidation:" + liquidationId,
                CorrelationId.current(),
                now,
                Map.of("cancelledOrders", Integer.toString(cancelled))));
        return liquidationId;
    }
}
