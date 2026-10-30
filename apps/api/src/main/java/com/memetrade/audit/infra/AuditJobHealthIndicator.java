package com.memetrade.audit.infra;

import com.memetrade.audit.domain.AuditJob;
import com.memetrade.audit.domain.AuditJobPolicy;
import com.memetrade.audit.domain.AuditJobRun;
import com.memetrade.audit.domain.AuditJobRunStore;
import com.memetrade.shared.health.HealthStatuses;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.dao.DataAccessException;

/**
 * Health components {@code auditRetention} and {@code auditOutboxCleanup} (NFR9.1, reliability-design.md): UP when the
 * last run COMPLETED and succeeded at most 26 h ago; DEGRADED without a row, with {@code FAILED} or
 * {@code TIME_LIMIT}, or with an older success. DEGRADED answers HTTP 200 and is not part of the readiness group, so
 * it never takes the API down (as in U1).
 */
public class AuditJobHealthIndicator implements HealthIndicator {

    private final AuditJob job;
    private final AuditJobRunStore runs;
    private final AuditJobPolicy policy;
    private final Clock clock;

    public AuditJobHealthIndicator(AuditJob job, AuditJobRunStore runs, AuditJobPolicy policy, Clock clock) {
        this.job = job;
        this.runs = runs;
        this.policy = policy;
        this.clock = clock;
    }

    @Override
    public Health health() {
        Optional<AuditJobRun> run;
        try {
            run = runs.find(job);
        } catch (DataAccessException unavailable) {
            // The database component already reports DOWN; the job itself is only degraded.
            return Health.status(HealthStatuses.DEGRADED)
                    .withDetail("job", job.name())
                    .withDetail("error", unavailable.getClass().getSimpleName())
                    .build();
        }
        Instant now = clock.instant();
        Health.Builder builder = policy.isHealthy(run, now) ? Health.up() : Health.status(HealthStatuses.DEGRADED);
        builder.withDetail("job", job.name());
        if (run.isEmpty()) {
            return builder.withDetail("lastResult", "NEVER_RUN").build();
        }
        builder.withDetail("lastResult", run.get().lastResult().name())
                .withDetail("lastRunAt", run.get().lastRunAt().toString());
        run.get().lastSuccessAt().ifPresent(success -> builder.withDetail("lastSuccessAt", success.toString()));
        AuditJobPolicy.sinceLastSuccess(run, now)
                .ifPresent(elapsed -> builder.withDetail("hoursSinceLastSuccess", elapsed.toHours()));
        return builder.build();
    }
}
