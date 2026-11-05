package com.memetrade.bots.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One parameter of a strategy template (entities.md {@code ParameterSpec}, BR1.2): the accepted range is inclusive and
 * a value is accepted only when {@code (value - min)} is a multiple of {@code step}.
 */
public record ParameterSpec(
        String key,
        String label,
        String unit,
        BigDecimal defaultValue,
        BigDecimal min,
        BigDecimal max,
        BigDecimal step) {

    public ParameterSpec {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(defaultValue, "defaultValue");
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        Objects.requireNonNull(step, "step");
    }
}
