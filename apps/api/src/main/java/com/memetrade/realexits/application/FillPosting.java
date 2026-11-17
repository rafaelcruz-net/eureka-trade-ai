package com.memetrade.realexits.application;

import com.memetrade.botwallet.api.RealChain;
import com.memetrade.ledger.api.RealEntry;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.realexits.domain.CancellationWatch;
import com.memetrade.realexits.domain.ConditionalOrder;
import com.memetrade.realexits.domain.ConditionalState;
import com.memetrade.realexits.domain.ConditionalStateMachine;
import com.memetrade.realexits.domain.ExitSide;
import com.memetrade.realexits.infra.JdbcConditionalStore;
import com.memetrade.realexits.infra.JdbcConditionalStore.Changes;
import com.memetrade.realexits.infra.RealExitsMetrics;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * BR5.2 to BR5.5, U7-NFR4.2: the fill of a conditional, confirmed on the network (finalized), enters the REAL ledger
 * through the M2 with the kinds that exist ({@code RealEntryRules}; no new kind, no change of CHECK):
 *
 * <ul>
 *   <li>one {@code SWAP_FILL_OUT}: the lamports received (positive) and the tokens sold, like the market sale of the
 *       U4; the lamports are the change of the bot wallet read on the network plus the fee it paid;
 *   <li>one {@code NETWORK_FEE} only when the bot wallet paid the fee of the execution (read on the network); paid by
 *       the Jupiter, nothing.
 * </ul>
 *
 * Reference {@code conditional:<id>}; keys {@code cond:<fill_signature>:fill} and {@code :fee} (they fit the
 * {@code varchar(200)} of the migration 0016, so no hash is needed), so one signature never enters the ledger twice. The
 * compare-and-set to FILLED comes FIRST, in the same transaction as the entries: a conditional moved meanwhile rolls
 * everything back and nothing stays half written. The position and the P&L REAL follow from the ledger.
 */
@Component
public class FillPosting {

    private static final Logger log = LoggerFactory.getLogger(FillPosting.class);

    private final JdbcConditionalStore store;
    private final RealLedger ledger;
    private final ExitEvents events;
    private final RealExitsMetrics metrics;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public FillPosting(
            JdbcConditionalStore store,
            RealLedger ledger,
            ExitEvents events,
            RealExitsMetrics metrics,
            Clock clock,
            PlatformTransactionManager manager) {
        this.store = store;
        this.ledger = ledger;
        this.events = events;
        this.metrics = metrics;
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
    }

    /**
     * @param effect the effect of the fill on the bot wallet, read on the network for {@code fillSignature}
     * @return true when this call moved the conditional to FILLED and recorded its entries
     */
    public boolean post(
            ConditionalOrder conditional, ExitSide side, String fillSignature, RealChain.SwapEffect effect) {
        if (!ConditionalStateMachine.FILLABLE.contains(conditional.state())) {
            return false;
        }
        long botFee = effect.feePaidByBot() ? effect.feeLamports() : 0;
        long received = Math.addExact(effect.botLamportsDelta(), botFee);
        long sold = effect.tokenBaseUnitsDelta() < 0
                ? Math.negateExact(effect.tokenBaseUnitsDelta())
                : conditional.amountBaseUnits();
        if (received <= 0 || sold <= 0) {
            // The network does not show a sale of the position for the bot wallet: never an entry made up.
            log.warn(
                    "REAL conditional fill not posted: conditional={}, the network shows no proceeds",
                    conditional.conditionalOrderId());
            return false;
        }
        Instant now = clock.instant();
        boolean posted = Boolean.TRUE.equals(transactions.execute(status -> {
            if (!store.move(
                    conditional.conditionalOrderId(),
                    conditional.state(),
                    ConditionalState.FILLED,
                    Changes.none()
                            .set("filled_side", side)
                            .set("fill_signature", fillSignature)
                            .set("filled_at", now)
                            .set("last_read_at", now),
                    now)) {
                return false;
            }
            String reference = "conditional:" + conditional.conditionalOrderId();
            ledger.record(new RealEntry(
                    conditional.tenantId(),
                    conditional.botWalletId(),
                    RealEntryKind.SWAP_FILL_OUT,
                    received,
                    fillSignature,
                    reference,
                    "cond:" + fillSignature + ":fill",
                    conditional.positionMint(),
                    sold));
            if (botFee > 0) {
                ledger.record(new RealEntry(
                        conditional.tenantId(),
                        conditional.botWalletId(),
                        RealEntryKind.NETWORK_FEE,
                        -botFee,
                        fillSignature,
                        reference,
                        "cond:" + fillSignature + ":fee"));
            }
            events.changed(
                    conditional, ConditionalState.FILLED, side.name(), Optional.empty(), Optional.of(fillSignature));
            return true;
        }));
        if (posted) {
            Instant seen = conditional.lastReadAt().orElse(now);
            metrics.filled(side, CancellationWatch.secondsSince(seen, now));
        }
        return posted;
    }
}
