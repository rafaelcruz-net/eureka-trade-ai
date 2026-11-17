package com.memetrade.realexits.domain;

import java.util.EnumSet;
import java.util.Set;

/** entities.md "Máquina de estados — ConditionalOrder": the seven states; four are final. */
public enum ConditionalState {
    PENDING_SIGNATURE,
    OPEN,
    CANCELLING,
    FILLED,
    CANCELLED,
    EXPIRED,
    FAILED;

    /** BR3.1, U7-NFR3.5: the states of the partial unique index (one per position). */
    public static final Set<ConditionalState> NOT_FINAL = EnumSet.of(PENDING_SIGNATURE, OPEN, CANCELLING);

    public boolean isFinal() {
        return !NOT_FINAL.contains(this);
    }
}
