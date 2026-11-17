package com.memetrade.realexits.infra;

import com.memetrade.botwallet.api.RealChain;
import com.memetrade.botwallet.api.RealExecutionWallet;
import com.memetrade.botwallet.api.RealUpstreamUnavailable;
import com.memetrade.botwallet.api.RealWalletInfo;
import com.memetrade.realexits.application.ExitEvents;
import com.memetrade.realexits.application.FillPosting;
import com.memetrade.realexits.domain.CancelReason;
import com.memetrade.realexits.domain.ConditionalOrder;
import com.memetrade.realexits.domain.ConditionalState;
import com.memetrade.realexits.domain.TriggerPort;
import com.memetrade.realexits.domain.TriggerPort.ProviderStatus;
import com.memetrade.realexits.infra.JdbcConditionalStore.Changes;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * F2, BR5.1 to BR5.6, U7-NFR4.4 to U7-NFR4.6: every 30 s in the worker, each conditional OPEN or CANCELLING is read at
 * the provider, with the tenant and the person recorded on its row:
 *
 * <ul>
 *   <li>open: the last successful read moves ({@code last_read_at}, base of the alert of 2 hours);
 *   <li>filled: the fill counts only after the network confirms its signature finalized; the quantity, the SOL and
 *       the fee come from the network ({@link FillPosting}); not finalized yet, it waits for the next run;
 *   <li>cancelled at the provider: a CANCELLING conditional is CANCELLED; an OPEN one ended without fill nor our
 *       cancellation and is EXPIRED;
 *   <li>expired: EXPIRED (CANCELLED when it was being cancelled).
 * </ul>
 *
 * The provider or the network down changes nothing and the last read does not move (U7-NFR4.6); the next run starts
 * from what the database holds.
 */
@Component
public class ConditionalReadJob {

    private static final Logger log = LoggerFactory.getLogger(ConditionalReadJob.class);
    private static final int BATCH = 200;

    private final JdbcConditionalStore store;
    private final TriggerPort trigger;
    private final RealChain chain;
    private final RealExecutionWallet wallets;
    private final FillPosting fills;
    private final ExitEvents events;
    private final RealExitsMetrics metrics;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public ConditionalReadJob(
            JdbcConditionalStore store,
            TriggerPort trigger,
            RealChain chain,
            RealExecutionWallet wallets,
            FillPosting fills,
            ExitEvents events,
            RealExitsMetrics metrics,
            Clock clock,
            PlatformTransactionManager manager) {
        this.store = store;
        this.trigger = trigger;
        this.chain = chain;
        this.wallets = wallets;
        this.fills = fills;
        this.events = events;
        this.metrics = metrics;
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
    }

    /** One run; returns how many conditionals changed state. */
    public int runOnce() {
        int changed = 0;
        boolean failed = false;
        for (ConditionalOrder conditional :
                store.inStates(List.of(ConditionalState.OPEN, ConditionalState.CANCELLING), BATCH)) {
            try {
                if (read(conditional)) {
                    changed++;
                }
            } catch (TriggerPort.TriggerUnavailable | RealUpstreamUnavailable down) {
                log.debug(
                        "REAL conditional not read: conditional={}, error={}",
                        conditional.conditionalOrderId(),
                        down.getClass().getSimpleName());
            } catch (RuntimeException failure) {
                failed = true;
                log.warn(
                        "REAL conditional read stopped: conditional={}, error={}",
                        conditional.conditionalOrderId(),
                        failure.getClass().getSimpleName());
            }
        }
        if (!failed) {
            metrics.jobRan("read");
        }
        return changed;
    }

    private boolean read(ConditionalOrder conditional) {
        Optional<String> address =
                wallets.wallet(conditional.tenantId(), conditional.userId()).map(RealWalletInfo::botWalletAddress);
        if (address.isEmpty()) {
            return false;
        }
        ProviderStatus status = trigger.status(conditional.ref(address.get()));
        Instant now = clock.instant();
        return switch (status) {
            case ProviderStatus.Open open -> {
                store.touch(
                        conditional.conditionalOrderId(),
                        conditional.state(),
                        Changes.none().set("last_read_at", now),
                        now);
                yield false;
            }
            case ProviderStatus.Filled filled -> filled(conditional, filled, address.get(), now);
            case ProviderStatus.Cancelled cancelled -> ended(conditional, cancelled.signature(), now);
            case ProviderStatus.Expired expired -> ended(conditional, Optional.empty(), now);
        };
    }

    /** BR5.2: finalized on the network first; the values of the network, never of the provider. */
    private boolean filled(ConditionalOrder conditional, ProviderStatus.Filled filled, String address, Instant now) {
        RealChain.TxStatus onChain = chain.status(filled.fillSignature());
        if (onChain.kind() != RealChain.TxStatus.Kind.FINALIZED) {
            store.touch(
                    conditional.conditionalOrderId(),
                    conditional.state(),
                    Changes.none().set("last_read_at", now),
                    now);
            return false;
        }
        RealChain.SwapEffect effect = chain.swapEffect(filled.fillSignature(), address, conditional.positionMint());
        return fills.post(conditional, filled.side(), filled.fillSignature(), effect);
    }

    /** The provider ended the conditional without a fill: CANCELLED when we were cancelling it, EXPIRED otherwise. */
    private boolean ended(ConditionalOrder conditional, Optional<String> signature, Instant now) {
        boolean cancelling = conditional.state() == ConditionalState.CANCELLING;
        ConditionalState to = cancelling ? ConditionalState.CANCELLED : ConditionalState.EXPIRED;
        return Boolean.TRUE.equals(transactions.execute(status -> {
            Changes changes = Changes.none().set("last_read_at", now);
            signature.ifPresent(value -> changes.set("cancel_signature", value));
            if (!store.move(conditional.conditionalOrderId(), conditional.state(), to, changes, now)) {
                return false;
            }
            if (cancelling) {
                events.cancelled(
                        conditional,
                        to,
                        conditional.cancelReason().orElse(CancelReason.USER),
                        Optional.empty(),
                        signature);
            } else {
                events.changed(conditional, to, "PROVIDER_ENDED", Optional.empty(), signature);
            }
            return true;
        }));
    }
}
