package com.memetrade.audit.infra;

import com.memetrade.audit.domain.AuditJob;
import com.memetrade.audit.domain.AuditJobResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Metrics of the daily jobs (observability-design.md, NFR9.2). The only label is {@code job} (plus {@code result} on
 * the run counter): fixed names, never an address, actor, tenant, event id or detail value.
 *
 * <ul>
 *   <li>{@code audit_job_runs_total{job,result}}
 *   <li>{@code audit_job_deleted_total{job}}
 *   <li>{@code audit_job_duration_seconds{job}}: histogram (1, 5, 15, 30, 35 min) to check the 30-minute limit
 *   <li>{@code audit_job_last_success_timestamp_seconds{job}}: epoch seconds of the last success; 0 when none
 * </ul>
 */
@Component
public class AuditJobMetrics {

    private final MeterRegistry registry;
    private final Map<AuditJob, AtomicLong> lastSuccess = new EnumMap<>(AuditJob.class);
    private final Map<AuditJob, Counter> deleted = new EnumMap<>(AuditJob.class);
    private final Map<AuditJob, Timer> duration = new EnumMap<>(AuditJob.class);

    public AuditJobMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (AuditJob job : AuditJob.values()) {
            AtomicLong success = new AtomicLong();
            lastSuccess.put(job, success);
            Gauge.builder("audit_job_last_success_timestamp_seconds", success, AtomicLong::doubleValue)
                    .description("Epoch seconds of the last COMPLETED run of the job; 0 when it never succeeded")
                    .tag("job", job.name())
                    .register(registry);
            deleted.put(
                    job,
                    Counter.builder("audit_job_deleted")
                            .description("Rows deleted by the daily audit job")
                            .tag("job", job.name())
                            .register(registry));
            duration.put(
                    job,
                    Timer.builder("audit_job_duration")
                            .description("Duration of a run of the daily audit job")
                            .tag("job", job.name())
                            .serviceLevelObjectives(
                                    Duration.ofMinutes(1),
                                    Duration.ofMinutes(5),
                                    Duration.ofMinutes(15),
                                    Duration.ofMinutes(30),
                                    Duration.ofMinutes(35))
                            .register(registry));
            for (AuditJobResult result : AuditJobResult.values()) {
                runs(job, result);
            }
        }
    }

    /** One finished run. */
    public void finished(AuditJob job, AuditJobResult result, long rowsDeleted, Duration took, Instant endedAt) {
        runs(job, result).increment();
        deleted.get(job).increment(rowsDeleted);
        duration.get(job).record(took.isNegative() ? Duration.ZERO : took);
        if (result == AuditJobResult.COMPLETED) {
            lastSuccess.get(job).set(endedAt.getEpochSecond());
        }
    }

    /** Seeds the last-success gauge from {@code audit_job_run} (startup). */
    public void lastSuccessAt(AuditJob job, Instant success) {
        lastSuccess.get(job).set(success.getEpochSecond());
    }

    /** Current value of the last-success gauge, in epoch seconds. */
    public long lastSuccessEpochSeconds(AuditJob job) {
        return lastSuccess.get(job).get();
    }

    private Counter runs(AuditJob job, AuditJobResult result) {
        return Counter.builder("audit_job_runs")
                .description("Runs of the daily audit jobs by result")
                .tag("job", job.name())
                .tag("result", result.name())
                .register(registry);
    }
}
