package com.memetrade.realexecution.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 261113-trading-real-bot U7 (BR5.5, logical-components.md "Evento real.conditional.state"): declared next to the M6
 * and implemented by {@code realexits} (inversion of dependency, as the other extension interfaces). The live channel
 * of the dashboard pulls the changes of the conditionals of ONE person the same way it pulls the REAL orders; each
 * change is written in the transaction of the change of state, so a change rolled back is never announced. Without
 * the module the empty implementation announces nothing.
 */
public interface RealExitChanges {

    /** The newest change of a conditional of the person, in milliseconds; 0 without any. */
    long changeMark(UUID tenantId, UUID userId);

    /** The conditionals of the person changed strictly after {@code since}, oldest change first, at most {@code limit}. */
    List<ConditionalChange> changedSince(UUID tenantId, UUID userId, Instant since, int limit);

    /** One change of state, as {@code real.conditional.state} carries it (no address, no transaction). */
    record ConditionalChange(
            UUID conditionalOrderId, String positionMint, String state, Optional<String> filledSide, Instant at) {

        public ConditionalChange {
            Objects.requireNonNull(conditionalOrderId, "conditionalOrderId");
            Objects.requireNonNull(positionMint, "positionMint");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(filledSide, "filledSide");
            Objects.requireNonNull(at, "at");
        }
    }
}
