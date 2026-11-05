package com.memetrade.decision.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One filter of the token selection (C4 {@code TokenSelection.rules}, BR1.3, BR4.9): a scanner field, an inclusive
 * operator and a non-negative decimal.
 */
public record SelectionRule(Field field, Operator operator, BigDecimal value) {

    public enum Field {
        LIQUIDITY,
        VOLUME_24H,
        HOLDERS,
        AGE_MINUTES,
        RISK_SCORE
    }

    /** Inclusive bounds (BR4.9): {@code GTE} is {@code >=}, {@code LTE} is {@code <=}. */
    public enum Operator {
        GTE,
        LTE
    }

    public SelectionRule {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(value, "value");
    }

    /** True when the known {@code observed} value passes this rule (fronteiras inclusivas). */
    public boolean accepts(BigDecimal observed) {
        int comparison = observed.compareTo(value);
        return operator == Operator.GTE ? comparison >= 0 : comparison <= 0;
    }
}
