package com.memetrade.realexecution.api;

import java.util.EnumSet;
import java.util.Set;

/** States of a REAL order (functional-spec.md, state machine of RealOrder; contract R3 {@code RealOrderState}). */
public enum RealOrderState {
    PROPOSED,
    QUOTED,
    SIGNED,
    SENT,
    CONFIRMED,
    FAILED,
    AMBIGUOUS,
    CANCELLED;

    /** BR2.2: the states of an order in progress (one per bot, one sell per person and mint). */
    public static final Set<RealOrderState> IN_PROGRESS = EnumSet.of(PROPOSED, QUOTED, SIGNED, SENT, AMBIGUOUS);

    /** The states that were never sent to the network: cancelling them is always safe (BR5.1). */
    public static final Set<RealOrderState> UNSENT = EnumSet.of(PROPOSED, QUOTED, SIGNED);

    public boolean isFinal() {
        return this == CONFIRMED || this == FAILED || this == CANCELLED;
    }
}
