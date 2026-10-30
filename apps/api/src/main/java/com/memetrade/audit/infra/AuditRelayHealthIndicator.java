package com.memetrade.audit.infra;

import com.memetrade.shared.health.HealthStatuses;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Health component {@code auditRelay}: DEGRADED when the oldest pending event is older than 60 s (NFR9.4). */
@Component("auditRelay")
public class AuditRelayHealthIndicator implements HealthIndicator {

    private final AuditOutboxRelay relay;
    private final AuditProperties properties;

    public AuditRelayHealthIndicator(AuditOutboxRelay relay, AuditProperties properties) {
        this.relay = relay;
        this.properties = properties;
    }

    @Override
    public Health health() {
        AuditOutboxRelay.OutboxLag lag;
        try {
            lag = relay.lag();
        } catch (org.springframework.dao.DataAccessException unavailable) {
            // The database component already reports DOWN; the relay itself is only degraded.
            return Health.status(HealthStatuses.DEGRADED)
                    .withDetail("error", unavailable.getClass().getSimpleName())
                    .build();
        }
        Health.Builder builder = lag.oldestAge().compareTo(properties.maxDeliveryLag()) > 0
                ? Health.status(HealthStatuses.DEGRADED)
                : Health.up();
        return builder.withDetail("pending", lag.pending())
                .withDetail("oldestAgeSeconds", lag.oldestAge().toSeconds())
                .build();
    }
}
