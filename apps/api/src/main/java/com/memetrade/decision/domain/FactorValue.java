package com.memetrade.decision.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The value of one factor of a decision: a known decimal, or explicitly unknown (never zero, NFR4.6). A sealed type
 * instead of a nullable decimal (team.md, "unknown como tipo explícito").
 */
public sealed interface FactorValue permits FactorValue.Known, FactorValue.Unknown {

    static FactorValue of(BigDecimal value) {
        return new Known(value);
    }

    static FactorValue unknown() {
        return Unknown.INSTANCE;
    }

    record Known(BigDecimal value) implements FactorValue {
        public Known {
            Objects.requireNonNull(value, "value");
        }
    }

    /** The value could not be computed because an input was UNKNOWN, missing or stale. */
    enum Unknown implements FactorValue {
        INSTANCE
    }
}
