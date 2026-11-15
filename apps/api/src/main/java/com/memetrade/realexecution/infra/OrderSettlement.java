package com.memetrade.realexecution.infra;

import com.memetrade.botwallet.api.RealChain;
import com.memetrade.botwallet.api.RealExecutionWallet;
import com.memetrade.botwallet.api.RealWalletInfo;
import com.memetrade.realexecution.api.RealOrderState;
import com.memetrade.realexecution.api.RealSide;
import com.memetrade.realexecution.domain.RealOrderStateMachine.ChainStatus;
import com.memetrade.realexecution.domain.RealOrderStateMachine.NetworkVerdict;
import com.memetrade.realexecution.infra.JdbcRealExecutionStore.OrderRow;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The end of an order on the network (BR5.1, BR5.2, BR6.1), shared by the confirmation and the ambiguous job: the
 * values read from the network (output, fee and who paid it), the entries of the REAL ledger and the transition, in
 * ONE transaction. The values are read BEFORE the transaction; nothing is ever sent from here.
 */
@Component
public class OrderSettlement {

    private final JdbcRealExecutionStore store;
    private final OrderTransitions transitions;
    private final RealOrderLedger ledger;
    private final RealExecutionWallet wallets;
    private final RealChain chain;
    private final RealExecutionProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public OrderSettlement(
            JdbcRealExecutionStore store,
            OrderTransitions transitions,
            RealOrderLedger ledger,
            RealExecutionWallet wallets,
            RealChain chain,
            RealExecutionConfig.CheckedSettings settings,
            Clock clock,
            PlatformTransactionManager manager) {
        this.store = store;
        this.transitions = transitions;
        this.ledger = ledger;
        this.wallets = wallets;
        this.chain = chain;
        this.properties = settings.properties();
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
    }

    /** @return whether this writer moved the order */
    public boolean settle(OrderRow order, NetworkVerdict verdict) {
        RealOrderState to = verdict.to();
        Map<String, Object> columns = new LinkedHashMap<>();
        Optional<RealChain.SwapEffect> effect = Optional.empty();
        Optional<RealWalletInfo> wallet = wallets.wallet(order.tenantId(), order.userId());
        boolean onChain = to == RealOrderState.CONFIRMED
                || (to == RealOrderState.FAILED
                        && verdict.code() != null
                        && !"BLOCKHASH_EXPIRED".equals(verdict.code().name()));
        if (onChain && wallet.isPresent()) {
            RealChain.SwapEffect read =
                    chain.swapEffect(order.txSignature(), wallet.get().botWalletAddress(), order.mint());
            effect = Optional.of(read);
            columns.put("fee_lamports", read.feeLamports());
            columns.put("fee_payer", read.feePaidByBot() ? "BOT_WALLET" : "MAIN_WALLET");
            if (to == RealOrderState.CONFIRMED) {
                columns.put("out_base_units", outOf(order, read));
                columns.put(
                        "rent_lamports",
                        com.memetrade.realexecution.domain.RealLedgerEntries.rentOf(
                                order.side(),
                                to,
                                order.input(),
                                read.botLamportsDelta(),
                                read.tokenBaseUnitsDelta(),
                                read.feeLamports(),
                                read.feePaidByBot()));
            }
        }
        Optional<RealChain.SwapEffect> settledEffect = effect;
        return Boolean.TRUE.equals(transactions.execute(status -> {
            boolean moved = transitions.move(
                    order,
                    to,
                    verdict.code() == null ? null : verdict.code().name(),
                    Optional.empty(),
                    columns,
                    Optional.empty(),
                    properties.lease());
            if (moved && to.isFinal()) {
                store.resolvePendingAttempts(order.realOrderId(), "ACCEPTED", clock.instant());
                settledEffect.ifPresent(read -> wallet.ifPresent(
                        w -> ledger.settle(store.find(order.realOrderId()).orElseThrow(), w.botWalletId(), to, read)));
            }
            return moved;
        }));
    }

    /** The output of the swap: base units received by a buy, lamports received by a sell (fee added back). */
    static long outOf(OrderRow order, RealChain.SwapEffect effect) {
        if (order.side() == RealSide.BUY) {
            return Math.max(0, effect.tokenBaseUnitsDelta());
        }
        long received = effect.feePaidByBot()
                ? Math.addExact(effect.botLamportsDelta(), effect.feeLamports())
                : effect.botLamportsDelta();
        return Math.max(0, received);
    }

    static ChainStatus statusOf(RealChain.TxStatus status) {
        return switch (status.kind()) {
            case FINALIZED -> ChainStatus.FINALIZED;
            case FAILED -> status.slippage() ? ChainStatus.FAILED_SLIPPAGE : ChainStatus.FAILED;
            case PENDING -> ChainStatus.PENDING;
            case NOT_FOUND -> ChainStatus.NOT_FOUND;
        };
    }
}
