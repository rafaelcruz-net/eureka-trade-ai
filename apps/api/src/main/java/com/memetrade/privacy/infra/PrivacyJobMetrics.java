package com.memetrade.privacy.infra;

import com.memetrade.privacy.domain.purge.PrivacyPurge;
import com.memetrade.privacy.domain.purge.PurgeResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Metrics of the Privacy purges (monitoring-design.md): the same names as the purges of u2-retention, with the label
 * {@code purge="dataSubjectRequestPurge"} - {@code memetrade_purge_deleted_total}, {@code memetrade_purge_runs_total},
 * {@code memetrade_purge_last_success_seconds} (cached, NaN before the first success) and
 * {@code memetrade_purge_batch_duration_seconds}. Never a tenant, a person or a request.
 */
@Component
public class PrivacyJobMetrics {

    private final MeterRegistry registry;
    private final Map<PrivacyPurge, AtomicReference<Instant>> lastSuccess = new EnumMap<>(PrivacyPurge.class);
    private final Map<PrivacyPurge, Counter> deleted = new EnumMap<>(PrivacyPurge.class);
    private final Map<PrivacyPurge, Timer> batches = new EnumMap<>(PrivacyPurge.class);

    public PrivacyJobMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (PrivacyPurge purge : PrivacyPurge.values()) {
            AtomicReference<Instant> success = new AtomicReference<>();
            lastSuccess.put(purge, success);
            Gauge.builder("memetrade.purge.last.success", success, PrivacyJobMetrics::epochSecondsOrNaN)
                    .description("Epoch seconds of the last successful run of the purge; NaN before the first one")
                    .baseUnit("seconds")
                    .tag("purge", purge.purgeId())
                    .register(registry);
            deleted.put(
                    purge,
                    Counter.builder("memetrade.purge.deleted")
                            .description("Rows deleted by the purge")
                            .tag("purge", purge.purgeId())
                            .register(registry));
            batches.put(
                    purge,
                    Timer.builder("memetrade.purge.batch.duration")
                            .description("Duration of one batch transaction of the purge")
                            .tag("purge", purge.purgeId())
                            .register(registry));
            for (PurgeResult result : PurgeResult.values()) {
                runs(purge, result);
            }
        }
    }

    public void batch(PrivacyPurge purge, Duration took) {
        batches.get(purge).record(took.isNegative() ? Duration.ZERO : took);
    }

    public void finished(PrivacyPurge purge, PurgeResult result, long rowsDeleted, Instant endedAt) {
        runs(purge, result).increment();
        deleted.get(purge).increment(rowsDeleted);
        if (result == PurgeResult.SUCCEEDED) {
            lastSuccess.get(purge).set(endedAt);
        }
    }

    /** Seeds the cached last success from {@code privacy_job_run} (startup). */
    public void lastSuccessAt(PrivacyPurge purge, Instant success) {
        lastSuccess
                .get(purge)
                .accumulateAndGet(
                        success, (current, seeded) -> current == null || seeded.isAfter(current) ? seeded : current);
    }

    private Counter runs(PrivacyPurge purge, PurgeResult result) {
        return Counter.builder("memetrade.purge.runs")
                .description("Runs of the purge by result")
                .tag("purge", purge.purgeId())
                .tag("result", result.name())
                .register(registry);
    }

    private static double epochSecondsOrNaN(AtomicReference<Instant> success) {
        Instant value = success.get();
        return value == null ? Double.NaN : value.getEpochSecond();
    }
}
