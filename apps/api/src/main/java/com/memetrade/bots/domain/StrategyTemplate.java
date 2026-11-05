package com.memetrade.bots.domain;

import com.memetrade.decision.domain.StrategyId;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** A versioned template strategy (entities.md {@code StrategyTemplate}, FR7.1). */
public record StrategyTemplate(
        StrategyId templateId, String version, String name, String description, List<ParameterSpec> parameters) {

    public StrategyTemplate {
        Objects.requireNonNull(templateId, "templateId");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        parameters = List.copyOf(parameters);
    }

    public Optional<ParameterSpec> parameter(String key) {
        return parameters.stream().filter(spec -> spec.key().equals(key)).findFirst();
    }

    /** The default values of every parameter, in template order (BR1.1). */
    public Map<String, BigDecimal> defaults() {
        Map<String, BigDecimal> defaults = new LinkedHashMap<>();
        parameters.forEach(spec -> defaults.put(spec.key(), spec.defaultValue()));
        return defaults;
    }
}
