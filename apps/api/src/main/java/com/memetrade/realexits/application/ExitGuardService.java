package com.memetrade.realexits.application;

import com.memetrade.realexecution.api.PositionExitGuard;
import com.memetrade.realexits.domain.CancelReason;
import com.memetrade.realexits.domain.ConditionalOrder;
import com.memetrade.realexits.domain.ConditionalState;
import com.memetrade.realexits.infra.JdbcConditionalStore;
import com.memetrade.realexits.infra.JdbcConditionalStore.Changes;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@link PositionExitGuard} of the M6 (BR4.3, BR6.5, U7-NFR4.3, logical-components.md "Venda atrás do
 * PositionExitGuard"). In the transaction and under the person lock of the caller; never a lock of its own and never
 * the network:
 *
 * <ul>
 *   <li>no conditional that is not final on the position: READY;
 *   <li>PENDING_SIGNATURE: CANCELLED at once (BR6.5, no transaction exists yet), READY;
 *   <li>OPEN: CANCELLING with {@code MARKET_SELL} (only the database), WAIT; the cancellation job sends it;
 *   <li>CANCELLING: WAIT.
 * </ul>
 *
 * Both changes go through the state machine with a compare-and-set, like every other change (iteration 2).
 */
@Service
public class ExitGuardService implements PositionExitGuard {

    private final JdbcConditionalStore store;
    private final ExitEvents events;
    private final Clock clock;

    public ExitGuardService(JdbcConditionalStore store, ExitEvents events, Clock clock) {
        this.store = store;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ExitRelease releaseForSale(UUID tenantId, UUID userId, String mint) {
        Optional<ConditionalOrder> held = store.lockNotFinalForPosition(tenantId, userId, mint);
        if (held.isEmpty()) {
            return ExitRelease.READY;
        }
        ConditionalOrder conditional = held.get();
        Instant now = clock.instant();
        return switch (conditional.state()) {
            case PENDING_SIGNATURE -> {
                if (store.move(
                        conditional.conditionalOrderId(),
                        ConditionalState.PENDING_SIGNATURE,
                        ConditionalState.CANCELLED,
                        Changes.none().set("cancel_reason", CancelReason.MARKET_SELL),
                        now)) {
                    events.cancelled(conditional, ConditionalState.CANCELLED, CancelReason.MARKET_SELL);
                }
                yield ExitRelease.READY;
            }
            case OPEN -> {
                if (store.move(
                        conditional.conditionalOrderId(),
                        ConditionalState.OPEN,
                        ConditionalState.CANCELLING,
                        Changes.none()
                                .set("cancel_reason", CancelReason.MARKET_SELL)
                                .set("cancelling_since", now),
                        now)) {
                    events.cancelled(conditional, ConditionalState.CANCELLING, CancelReason.MARKET_SELL);
                }
                yield ExitRelease.WAIT;
            }
            default -> ExitRelease.WAIT;
        };
    }
}
