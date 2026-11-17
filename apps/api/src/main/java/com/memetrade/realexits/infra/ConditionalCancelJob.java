package com.memetrade.realexits.infra;

import com.memetrade.botwallet.api.RealChain;
import com.memetrade.botwallet.api.RealExecutionWallet;
import com.memetrade.botwallet.api.RealKillSwitchState;
import com.memetrade.botwallet.api.RealOperationMode;
import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.botwallet.api.RealUpstreamUnavailable;
import com.memetrade.botwallet.api.RealWalletInfo;
import com.memetrade.realexits.application.ExitEvents;
import com.memetrade.realexits.application.FillPosting;
import com.memetrade.realexits.domain.CancelReason;
import com.memetrade.realexits.domain.ConditionalOrder;
import com.memetrade.realexits.domain.ConditionalState;
import com.memetrade.realexits.domain.ConditionalStateMachine;
import com.memetrade.realexits.domain.TriggerPort;
import com.memetrade.realexits.domain.TriggerPort.CancelResult;
import com.memetrade.realexits.domain.TriggerPort.ProviderStatus;
import com.memetrade.realexits.infra.JdbcConditionalStore.Changes;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * F3, BR6.1 to BR6.6, U7-NFR3.2, U7-NFR4.5: every minute in the worker, in two phases, each conditional with the tenant
 * and the person recorded on its row.
 *
 * <ol>
 *   <li>The triggers: the kill switch REAL (M7), the mode of the person out of AUTO_EXITS (BR6.2) and the release no
 *       longer in force (BR6.3) cancel the conditionals PENDING_SIGNATURE (CANCELLED at once, BR6.5) and OPEN
 *       (CANCELLING); a signing session past its deadline is EXPIRED.
 *   <li>Every conditional CANCELLING: the provider is read first (a fill before the cancellation counts, BR6.6);
 *       otherwise the cancellation is sent again, signed only by the bot wallet in the TxSigner (BR1.3). Confirmed:
 *       CANCELLED; sent and not confirmed: it stays CANCELLING with one attempt more. It never goes back to OPEN.
 * </ol>
 *
 * The job never looks at the exits flag, the REAL switch nor the pauses: the cancellation works like the withdrawal,
 * with the kill switch on and the REAL off (BR1.3, S39). The alert of 15 minutes comes from the gauge of the oldest
 * cancellation (U7-NFR5.7).
 */
@Component
public class ConditionalCancelJob {

    private static final Logger log = LoggerFactory.getLogger(ConditionalCancelJob.class);
    private static final int BATCH = 200;

    /** BR6.3: the release states that are no longer in force for a conditional. */
    private static final Set<String> RELEASE_GONE = Set.of("REVOKED", "NONE");

    private final JdbcConditionalStore store;
    private final TriggerPort trigger;
    private final RealChain chain;
    private final RealExecutionWallet wallets;
    private final RealStateQuery states;
    private final RealKillSwitchState killSwitch;
    private final FillPosting fills;
    private final ExitEvents events;
    private final RealExitsMetrics metrics;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public ConditionalCancelJob(
            JdbcConditionalStore store,
            TriggerPort trigger,
            RealChain chain,
            RealExecutionWallet wallets,
            RealStateQuery states,
            RealKillSwitchState killSwitch,
            FillPosting fills,
            ExitEvents events,
            RealExitsMetrics metrics,
            Clock clock,
            PlatformTransactionManager manager) {
        this.store = store;
        this.trigger = trigger;
        this.chain = chain;
        this.wallets = wallets;
        this.states = states;
        this.killSwitch = killSwitch;
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
        boolean killSwitchOn = killSwitch.current().active();
        for (ConditionalOrder conditional :
                store.inStates(List.of(ConditionalState.PENDING_SIGNATURE, ConditionalState.OPEN), BATCH)) {
            try {
                changed += trigger(conditional, killSwitchOn) ? 1 : 0;
            } catch (RuntimeException failure) {
                failed = true;
                log.warn(
                        "REAL conditional not checked for cancellation: conditional={}, error={}",
                        conditional.conditionalOrderId(),
                        failure.getClass().getSimpleName());
            }
        }
        for (ConditionalOrder conditional : store.inStates(List.of(ConditionalState.CANCELLING), BATCH)) {
            try {
                changed += cancel(conditional) ? 1 : 0;
            } catch (TriggerPort.TriggerUnavailable | RealUpstreamUnavailable down) {
                log.debug(
                        "REAL conditional cancellation waits: conditional={}, error={}",
                        conditional.conditionalOrderId(),
                        down.getClass().getSimpleName());
            } catch (RuntimeException failure) {
                failed = true;
                log.warn(
                        "REAL conditional cancellation stopped: conditional={}, error={}",
                        conditional.conditionalOrderId(),
                        failure.getClass().getSimpleName());
            }
        }
        if (!failed) {
            metrics.jobRan("cancel");
        }
        return changed;
    }

    /** Phase 1: the reason to cancel this conditional now, if any. */
    private boolean trigger(ConditionalOrder conditional, boolean killSwitchOn) {
        Instant now = clock.instant();
        Optional<CancelReason> reason = reason(conditional, killSwitchOn);
        if (reason.isEmpty()) {
            if (conditional.state() == ConditionalState.PENDING_SIGNATURE
                    && conditional
                            .signingDeadline()
                            .filter(deadline -> !now.isBefore(deadline))
                            .isPresent()) {
                return expire(conditional, now);
            }
            return false;
        }
        ConditionalState to =
                ConditionalStateMachine.cancellationTarget(conditional.state()).orElseThrow();
        Changes changes = Changes.none().set("cancel_reason", reason.get());
        if (to == ConditionalState.CANCELLING) {
            changes.set("cancelling_since", now);
        }
        return Boolean.TRUE.equals(transactions.execute(status -> {
            if (!store.move(conditional.conditionalOrderId(), conditional.state(), to, changes, now)) {
                return false;
            }
            events.cancelled(conditional, to, reason.get());
            return true;
        }));
    }

    private Optional<CancelReason> reason(ConditionalOrder conditional, boolean killSwitchOn) {
        if (killSwitchOn) {
            return Optional.of(CancelReason.KILL_SWITCH);
        }
        String release =
                states.readRelease(conditional.tenantId(), conditional.userId()).state();
        Optional<RealWalletInfo> wallet = wallets.wallet(conditional.tenantId(), conditional.userId());
        if (RELEASE_GONE.contains(release) || wallet.isEmpty()) {
            return Optional.of(CancelReason.RELEASE_REVOKED);
        }
        if (wallet.get().mode() != RealOperationMode.AUTO_EXITS) {
            return Optional.of(CancelReason.MODE_CHANGE);
        }
        return Optional.empty();
    }

    private boolean expire(ConditionalOrder conditional, Instant now) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            if (!store.move(
                    conditional.conditionalOrderId(),
                    ConditionalState.PENDING_SIGNATURE,
                    ConditionalState.EXPIRED,
                    Changes.none(),
                    now)) {
                return false;
            }
            events.changed(
                    conditional,
                    ConditionalState.EXPIRED,
                    "SIGNING_DEADLINE_EXPIRED",
                    Optional.empty(),
                    Optional.empty());
            return true;
        }));
    }

    /** Phase 2: read the provider first (BR6.6), then send the cancellation again. */
    private boolean cancel(ConditionalOrder conditional) {
        Optional<String> address =
                wallets.wallet(conditional.tenantId(), conditional.userId()).map(RealWalletInfo::botWalletAddress);
        TriggerPort.OrderRef ref = conditional.ref(address.orElse(null));
        ProviderStatus status = trigger.status(ref);
        Instant now = clock.instant();
        if (status instanceof ProviderStatus.Filled filled && address.isPresent()) {
            RealChain.TxStatus onChain = chain.status(filled.fillSignature());
            if (onChain.kind() == RealChain.TxStatus.Kind.FINALIZED) {
                return fills.post(
                        conditional,
                        filled.side(),
                        filled.fillSignature(),
                        chain.swapEffect(filled.fillSignature(), address.get(), conditional.positionMint()));
            }
            return false;
        }
        if (status instanceof ProviderStatus.Cancelled cancelled) {
            return confirmed(conditional, cancelled.signature(), now);
        }
        if (status instanceof ProviderStatus.Expired) {
            return confirmed(conditional, Optional.empty(), now);
        }
        return switch (trigger.cancel(ref)) {
            case CancelResult.Cancelled cancelled -> confirmed(conditional, cancelled.signature(), now);
            case CancelResult.Submitted submitted -> {
                store.touch(
                        conditional.conditionalOrderId(),
                        ConditionalState.CANCELLING,
                        Changes.none()
                                .set("cancel_signature", submitted.signature())
                                .countAttempt(),
                        now);
                yield false;
            }
        };
    }

    private boolean confirmed(ConditionalOrder conditional, Optional<String> signature, Instant now) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            Changes changes = Changes.none();
            signature.ifPresent(value -> changes.set("cancel_signature", value));
            if (!store.move(
                    conditional.conditionalOrderId(),
                    ConditionalState.CANCELLING,
                    ConditionalState.CANCELLED,
                    changes,
                    now)) {
                return false;
            }
            events.cancelled(
                    conditional,
                    ConditionalState.CANCELLED,
                    conditional.cancelReason().orElse(CancelReason.USER),
                    Optional.empty(),
                    signature);
            return true;
        }));
    }
}
