package com.memetrade.identity.infra;

import com.memetrade.identity.domain.purge.IdentityPurge;
import com.memetrade.identity.domain.purge.PurgeResult;
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
 * Metrics of the Identity purges (observability-design.md NFR8.1). The only labels are {@code purge} (the purge id) and
 * {@code result}: fixed names, never a wallet, nonce, digest, tenant or person.
 *
 * <ul>
 *   <li>{@code memetrade_purge_deleted_total{purge}}: rows of the main table deleted
 *   <li>{@code memetrade_purge_runs_total{purge,result}}
 *   <li>{@code memetrade_purge_last_success_seconds{purge}}: epoch seconds of the last success, from a cached value
 *       updated at the end of each run and at startup; NaN before the first one. Collecting never reads the database
 *       and never throws.
 *   <li>{@code memetrade_purge_batch_duration_seconds{purge}}: duration of each batch (NFR3.1, 500 ms budget)
 * </ul>
 */
@Component
public class IdentityJobMetrics {

    private final MeterRegistry registry;
    private final Map<IdentityPurge, AtomicReference<Instant>> lastSuccess = new EnumMap<>(IdentityPurge.class);
    private final Map<IdentityPurge, Counter> deleted = new EnumMap<>(IdentityPurge.class);
    private final Map<IdentityPurge, Timer> batches = new EnumMap<>(IdentityPurge.class);

    public IdentityJobMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (IdentityPurge purge : IdentityPurge.values()) {
            AtomicReference<Instant> success = new AtomicReference<>();
            lastSuccess.put(purge, success);
            Gauge.builder("memetrade.purge.last.success", success, IdentityJobMetrics::epochSecondsOrNaN)
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

    /** One batch, successful or not. */
    public void batch(IdentityPurge purge, Duration took) {
        batches.get(purge).record(took.isNegative() ? Duration.ZERO : took);
    }

    /** One finished run. */
    public void finished(IdentityPurge purge, PurgeResult result, long rowsDeleted, Instant endedAt) {
        runs(purge, result).increment();
        deleted.get(purge).increment(rowsDeleted);
        if (result == PurgeResult.SUCCEEDED) {
            lastSuccess.get(purge).set(endedAt);
        }
    }

    /** Seeds the cached last success from {@code identity_job_run} (startup). */
    public void lastSuccessAt(IdentityPurge purge, Instant success) {
        lastSuccess
                .get(purge)
                .accumulateAndGet(
                        success, (current, seeded) -> current == null || seeded.isAfter(current) ? seeded : current);
    }

    private Counter runs(IdentityPurge purge, PurgeResult result) {
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
