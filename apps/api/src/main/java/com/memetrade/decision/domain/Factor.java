package com.memetrade.decision.domain;

import com.memetrade.marketdata.api.TokenId;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * One factor of a decision (C4 {@code Decision.factors} with the U5 addition {@code token}): which token, what was
 * measured, against which threshold, and whether it was satisfied. {@code label} carries a non-decimal qualifier such
 * as the risk band ({@code LOW}, {@code MEDIUM}, {@code HIGH}); {@code threshold} is empty when the factor has none.
 */
public record Factor(
        TokenId token,
        String name,
        FactorValue value,
        Optional<BigDecimal> threshold,
        boolean satisfied,
        Optional<String> label) {

    public Factor {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(threshold, "threshold");
        Objects.requireNonNull(label, "label");
    }

    public static Factor measured(TokenId token, String name, FactorValue value, BigDecimal threshold, boolean ok) {
        return new Factor(token, name, value, Optional.of(threshold), ok, Optional.empty());
    }
}
