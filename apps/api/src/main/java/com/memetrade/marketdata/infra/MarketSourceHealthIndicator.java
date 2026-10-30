package com.memetrade.marketdata.infra;

import com.memetrade.marketdata.api.SourceStatus;
import com.memetrade.shared.health.HealthStatuses;
import java.time.Duration;
import java.util.Optional;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health component {@code marketSource}: UP while the source is OK and collected less than 60 s ago (NFR1.2,
 * NFR9.5); otherwise DEGRADED. The details expose status and lastUpdatedAt, which scripts/verify.sh waits for.
 */
@Component("marketSource")
public class MarketSourceHealthIndicator implements HealthIndicator {

    static final Duration MAX_COLLECTION_AGE = Duration.ofSeconds(60);

    private final MarketCollectorJob job;

    public MarketSourceHealthIndicator(MarketCollectorJob job) {
        this.job = job;
    }

    @Override
    public Health health() {
        Optional<SourceStatus> status = job.status();
        Optional<Duration> age = job.collectionAge();
        boolean healthy = status.map(s -> s.status() == SourceStatus.State.OK).orElse(false)
                && age.map(a -> a.compareTo(MAX_COLLECTION_AGE) < 0).orElse(false);
        Health.Builder builder = healthy ? Health.up() : Health.status(HealthStatuses.DEGRADED);
        builder.withDetail("status", status.map(s -> s.status().name()).orElse("NEVER_COLLECTED"));
        status.flatMap(SourceStatus::lastUpdatedAt).ifPresent(at -> builder.withDetail("lastUpdatedAt", at.toString()));
        status.ifPresent(s -> builder.withDetail("simulated", s.source().simulated()));
        return builder.build();
    }
}
