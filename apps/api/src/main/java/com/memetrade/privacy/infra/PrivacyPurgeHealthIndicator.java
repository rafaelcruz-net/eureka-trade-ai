package com.memetrade.privacy.infra;

import com.memetrade.privacy.domain.purge.PrivacyPurge;
import com.memetrade.privacy.domain.purge.PurgeHealthPolicy;
import com.memetrade.privacy.domain.purge.PurgeRun;
import com.memetrade.privacy.domain.purge.PurgeRunStore;
import com.memetrade.shared.health.HealthStatuses;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.dao.DataAccessException;

/**
 * Health component {@code dataSubjectRequestPurge} (NFR7.1, monitoring-design.md): reads the row of the purge in
 * {@code privacy_job_run}, so every instance shows the same state. UP during the first 48 hours after the start or while
 * the last success is at most 48 hours old; DEGRADED otherwise (HTTP 200, out of liveness and readiness). The details
 * carry the purge, the last result and the instants only.
 */
public class PrivacyPurgeHealthIndicator implements HealthIndicator {

    private final PrivacyPurge purge;
    private final PurgeRunStore runs;
    private final PurgeHealthPolicy policy;
    private final Instant processStartedAt;
    private final Clock clock;

    public PrivacyPurgeHealthIndicator(
            PrivacyPurge purge, PurgeRunStore runs, PurgeHealthPolicy policy, Instant processStartedAt, Clock clock) {
        this.purge = Objects.requireNonNull(purge, "purge");
        this.runs = runs;
        this.policy = policy;
        this.processStartedAt = Objects.requireNonNull(processStartedAt, "processStartedAt");
        this.clock = clock;
    }

    @Override
    public Health health() {
        Optional<PurgeRun> run;
        try {
            run = runs.find(purge);
        } catch (DataAccessException unavailable) {
            // The database component already reports DOWN; the purge itself is only degraded.
            return Health.status(HealthStatuses.DEGRADED)
                    .withDetail("purge", purge.purgeId())
                    .withDetail("error", unavailable.getClass().getSimpleName())
                    .build();
        }
        Health.Builder builder = policy.isHealthy(run, processStartedAt, clock.instant())
                ? Health.up()
                : Health.status(HealthStatuses.DEGRADED);
        builder.withDetail("purge", purge.purgeId());
        if (run.isEmpty()) {
            return builder.withDetail("lastResult", "NEVER_RUN").build();
        }
        builder.withDetail("lastResult", run.get().lastResult().name())
                .withDetail("lastRunAt", run.get().lastRunAt().toString());
        run.get().lastSuccessAt().ifPresent(success -> builder.withDetail("lastSuccessAt", success.toString()));
        return builder.build();
    }
}
