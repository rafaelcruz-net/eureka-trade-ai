package com.memetrade.riskassessment.infra;

import com.memetrade.shared.health.HealthStatuses;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Health component {@code riskRecalculation} (NFR9.1, reliability-design.md): UP when the last successful
 * recalculation is at most 5 minutes old; DEGRADED (HTTP 200) when it is older or the last run failed; UNKNOWN before
 * the first run. Never DOWN, outside the readiness group.
 */
public class RiskRecalculationHealth implements HealthIndicator {

    static final Duration MAX_AGE = Duration.ofMinutes(5);

    private final RiskRecalculator recalculator;
    private final Clock clock;

    public RiskRecalculationHealth(RiskRecalculator recalculator, Clock clock) {
        this.recalculator = recalculator;
        this.clock = clock;
    }

    @Override
    public Health health() {
        Optional<Instant> success = recalculator.lastSuccess();
        if (success.isEmpty() && !recalculator.lastRunFailed()) {
            return Health.unknown().build();
        }
        boolean recent = success.map(at -> Duration.between(at, clock.instant()).compareTo(MAX_AGE) <= 0)
                .orElse(false);
        Health.Builder builder =
                recent && !recalculator.lastRunFailed() ? Health.up() : Health.status(HealthStatuses.DEGRADED);
        success.ifPresent(at -> builder.withDetail("lastSuccessAt", at.toString()));
        recalculator.lastFailure().ifPresent(at -> builder.withDetail("lastFailureAt", at.toString()));
        return builder.build();
    }
}
