package com.memetrade.realexecution.infra;

import com.memetrade.botwallet.api.RealChain;
import com.memetrade.ledger.api.RealEntry;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.realexecution.api.RealOrderState;
import com.memetrade.realexecution.domain.RealLedgerEntries;
import com.memetrade.realexecution.domain.RealOrderStateMachine;
import com.memetrade.realexecution.infra.JdbcRealExecutionStore.OrderRow;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The entries of an order in the REAL ledger (M2, BR6.1, BR5.3), in the transaction of the caller. Reference
 * {@code order:<realOrderId>}; idempotency key {@code <realOrderId>:<KIND>}, so a repeated step never records twice.
 */
@Component
public class RealOrderLedger {

    private final RealLedger ledger;

    public RealOrderLedger(RealLedger ledger) {
        this.ledger = ledger;
    }

    /** BR2.1: the RESERVE of a buy, recorded with the order. */
    public void reserve(UUID tenantId, UUID botWalletId, UUID realOrderId, long lamports) {
        ledger.record(new RealEntry(
                tenantId,
                botWalletId,
                RealEntryKind.RESERVE,
                lamports,
                null,
                reference(realOrderId),
                realOrderId + ":" + RealEntryKind.RESERVE));
    }

    /** BR5.3: the RESERVE_RELEASE of a buy that reached a final state. */
    public void releaseIfFinal(OrderRow order, UUID botWalletId, RealOrderState to) {
        if (RealOrderStateMachine.releasesReserve(order.side(), to)) {
            ledger.record(new RealEntry(
                    order.tenantId(),
                    botWalletId,
                    RealEntryKind.RESERVE_RELEASE,
                    order.inputLamports(),
                    order.txSignature(),
                    reference(order.realOrderId()),
                    order.realOrderId() + ":" + RealEntryKind.RESERVE_RELEASE));
        }
    }

    /** BR6.1: the entries of the network for an order CONFIRMED or FAILED on chain, with its signature. */
    public void settle(OrderRow order, UUID botWalletId, RealOrderState to, RealChain.SwapEffect effect) {
        List<RealLedgerEntries.Entry> entries = RealLedgerEntries.of(
                order.side(),
                to,
                order.input(),
                effect.botLamportsDelta(),
                effect.tokenBaseUnitsDelta(),
                effect.feeLamports(),
                effect.feePaidByBot());
        for (RealLedgerEntries.Entry entry : entries) {
            String key = order.realOrderId() + ":" + entry.kind();
            ledger.record(new RealEntry(
                    order.tenantId(),
                    botWalletId,
                    entry.kind(),
                    entry.lamports(),
                    order.txSignature(),
                    reference(order.realOrderId()),
                    key,
                    entry.tokenBaseUnits() == null ? null : order.mint(),
                    entry.tokenBaseUnits()));
        }
    }

    static String reference(UUID realOrderId) {
        return "order:" + realOrderId;
    }
}
