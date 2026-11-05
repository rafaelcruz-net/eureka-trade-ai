package com.memetrade.riskcontrol.infra;

import com.memetrade.riskcontrol.domain.EvaluationErrors;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The error metrics of U6 (NFR9.2, observability-design.md): {@code riskcontrol.evaluation.errors} with the label
 * {@code cause} only (MARKET_DATA, PORTFOLIO, DATABASE, OTHER), and {@code riskcontrol.purge.errors} without labels. No
 * person, tenant, bot, decision nor token ever becomes a label. Registered at start-up, so both exist at zero.
 */
@Component
public class RiskControlMetrics implements EvaluationErrors {

    static final String EVALUATION_ERRORS = "riskcontrol.evaluation.errors";
    static final String PURGE_ERRORS = "riskcontrol.purge.errors";

    private final Map<Cause, Counter> evaluationErrors = new EnumMap<>(Cause.class);
    private final Counter purgeErrors;

    public RiskControlMetrics(MeterRegistry registry) {
        for (Cause cause : Cause.values()) {
            evaluationErrors.put(
                    cause,
                    Counter.builder(EVALUATION_ERRORS)
                            .description("Technical failures of OrderGate.evaluate (the cycle was rolled back)")
                            .tag("cause", cause.name())
                            .register(registry));
        }
        this.purgeErrors = Counter.builder(PURGE_ERRORS)
                .description("Rounds of the purge of old approved evaluations that ended FAILED")
                .register(registry);
    }

    @Override
    public void count(Cause cause) {
        evaluationErrors.get(cause).increment();
    }

    public void purgeFailed() {
        purgeErrors.increment();
    }
}
