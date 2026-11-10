package com.memetrade.privacy.infra;

import com.memetrade.shared.health.HealthStatuses;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * Health component {@code dataRequests} (BR4.14; NFR8.2; AC5.7.1): DEGRADED (HTTP 200) while the cached count of open
 * requests past their due date is above zero, UP otherwise. It reads ONLY the cache of {@link DataRequestMetrics} (at
 * most 60 s old in any instance), never the database, and stays out of liveness and readiness. The details carry the
 * counts only.
 */
public class DataRequestsHealthIndicator implements HealthIndicator {

    private final DataRequestMetrics metrics;

    public DataRequestsHealthIndicator(DataRequestMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public Health health() {
        long overdue = metrics.overdue();
        Health.Builder builder = overdue > 0 ? Health.status(HealthStatuses.DEGRADED) : Health.up();
        builder.withDetail("overdue", overdue).withDetail("dueSoon", metrics.dueSoon());
        metrics.refreshedAt().ifPresent(at -> builder.withDetail("refreshedAt", at.toString()));
        return builder.build();
    }
}
