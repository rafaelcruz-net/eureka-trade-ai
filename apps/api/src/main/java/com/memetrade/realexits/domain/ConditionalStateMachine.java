package com.memetrade.realexits.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * entities.md "Máquina de estados — ConditionalOrder", pure. Every change of state of the application is a
 * compare-and-set on the current state (U7-NFR4.1) and passes {@link #require} first; the trigger of the migration 0022
 * refuses the same transitions in the database.
 */
public final class ConditionalStateMachine {

    /** BR5.4, BR6.6: a fill is accepted while OPEN or CANCELLING (the fill wins over the cancellation). */
    public static final Set<ConditionalState> FILLABLE = EnumSet.of(ConditionalState.OPEN, ConditionalState.CANCELLING);

    private static final Map<ConditionalState, Set<ConditionalState>> ALLOWED = new EnumMap<>(ConditionalState.class);

    static {
        ALLOWED.put(
                ConditionalState.PENDING_SIGNATURE,
                EnumSet.of(
                        ConditionalState.OPEN,
                        ConditionalState.EXPIRED,
                        ConditionalState.FAILED,
                        ConditionalState.CANCELLED));
        ALLOWED.put(
                ConditionalState.OPEN,
                EnumSet.of(ConditionalState.FILLED, ConditionalState.CANCELLING, ConditionalState.EXPIRED));
        ALLOWED.put(ConditionalState.CANCELLING, EnumSet.of(ConditionalState.CANCELLED, ConditionalState.FILLED));
    }

    private ConditionalStateMachine() {}

    public static boolean allows(ConditionalState from, ConditionalState to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /** @return {@code to} when the transition is in the table; otherwise an {@link IllegalStateException} */
    public static ConditionalState require(ConditionalState from, ConditionalState to) {
        if (!allows(from, to)) {
            throw new IllegalStateException("A conditional does not move from " + from + " to " + to);
        }
        return to;
    }

    /**
     * BR6.5: the state a cancellation moves a conditional to: CANCELLED directly from PENDING_SIGNATURE (no transaction
     * exists yet), CANCELLING from OPEN; nothing for a conditional already being cancelled or final.
     */
    public static Optional<ConditionalState> cancellationTarget(ConditionalState from) {
        return switch (from) {
            case PENDING_SIGNATURE -> Optional.of(ConditionalState.CANCELLED);
            case OPEN -> Optional.of(ConditionalState.CANCELLING);
            default -> Optional.empty();
        };
    }
}
