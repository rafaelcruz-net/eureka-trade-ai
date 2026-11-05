package com.memetrade.riskcontrol.domain;

import com.memetrade.riskcontrol.api.RejectionReason;
import java.util.Objects;

/**
 * The number that barred one reason (Q10, entities.md RejectionReasonDetail). {@code measured} is a decimal text in the
 * scale of the unit, or {@link #UNKNOWN} when the data was missing (never zero); for {@code DUPLICATE} it is the
 * decision id and {@code limit} the original evaluation id.
 */
public record ReasonDetail(RejectionReason reason, String measured, String limit, DetailUnit unit) {

    /** The text of a value that could not be measured (C-05, BR3.2). */
    public static final String UNKNOWN = "UNKNOWN";

    public ReasonDetail {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(unit, "unit");
        if (measured == null || measured.isBlank() || limit == null || limit.isBlank()) {
            throw new IllegalArgumentException("A reason detail has a measured value and a limit");
        }
    }
}
