package com.memetrade.identity.infra;

import com.memetrade.identity.domain.purge.IdentityPurge;
import com.memetrade.identity.domain.purge.PurgeHealthPolicy;
import com.memetrade.identity.domain.purge.PurgeRun;
import com.memetrade.identity.domain.purge.PurgeRunStore;
import com.memetrade.shared.health.HealthStatuses;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.dao.DataAccessException;

/**
 * Health components {@code loginChallengePurge} and {@code sessionPurge} (BR2.8, NFR7.2, NFR8.2): read the row of the
 * purge in {@code identity_job_run}, so every instance shows the same state. UP during the first 48 hours after the
 * start or while the last success is at most 48 hours old; DEGRADED otherwise. DEGRADED answers HTTP 200 and the
 * components stay out of the liveness and readiness groups, so a late purge never takes the api down. The details carry
 * the purge, the last result, the start of the last run and the last success; never a value of a row.
 */
public class PurgeHealthIndicator implements HealthIndicator {

    private final IdentityPurge purge;
    private final PurgeRunStore runs;
    private final PurgeHealthPolicy policy;
    private final Instant processStartedAt;
    private final Clock clock;

    public PurgeHealthIndicator(
            IdentityPurge purge, PurgeRunStore runs, PurgeHealthPolicy policy, Instant processStartedAt, Clock clock) {
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
        Instant now = clock.instant();
        Health.Builder builder =
                policy.isHealthy(run, processStartedAt, now) ? Health.up() : Health.status(HealthStatuses.DEGRADED);
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
