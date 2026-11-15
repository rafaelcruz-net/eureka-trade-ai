package com.memetrade.riskcontrol.real.infra;

import com.memetrade.riskcontrol.api.OrderSide;
import com.memetrade.riskcontrol.api.RealGateUnavailable;
import com.memetrade.riskcontrol.real.domain.RealVerdict;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 261113-trading-real-bot U3 (U3-NFR5.1 to U3-NFR5.8, observability-design.md, monitoring-design.md): the metrics of the
 * REAL barrier. Every label comes from an enum or a fixed job name: side, outcome, code, detail, dependency, state, job.
 * Never a mint, an address, a tenant nor a person (U3-NFR6.3). The error counters, the job counters and the job
 * gauges exist at zero from the start, so the alerts of a job that never ran are evaluated (monitoring-design.md).
 *
 * <p>Prometheus names: {@code real_risk_evaluations_total}, {@code real_risk_evaluation_seconds} (histogram),
 * {@code real_risk_lock_wait_seconds} (histogram), {@code real_risk_reservations_expired_total},
 * {@code real_risk_daily_loss_pauses_total}, {@code real_risk_errors_total}, {@code real_risk_job_runs_total},
 * {@code real_risk_job_last_success_seconds} and {@code real_risk_job_seconds}. The gauge
 * {@code real_risk_reservations} is registered only in the worker ({@link RealRiskReservationGauge}).
 */
@Component
public class RealRiskMetrics {

    static final String EVALUATIONS = "real_risk_evaluations";
    static final String EVALUATION = "real_risk_evaluation";
    static final String LOCK_WAIT = "real_risk_lock_wait";
    static final String EXPIRED = "real_risk_reservations_expired";
    static final String DAILY_LOSS_PAUSES = "real_risk_daily_loss_pauses";
    static final String ERRORS = "real_risk_errors";
    static final String JOB_RUNS = "real_risk_job_runs";
    static final String JOB_LAST_SUCCESS = "real_risk_job_last_success_seconds";
    static final String JOB = "real_risk_job";

    /** The jobs of the barrier (U3-NFR5.8): F3, F4 and F5 in the worker, the purge in the api. */
    public static final List<String> JOBS = List.of("day", "daily-loss", "reservation-expiry", "purge");

    /** U3-NFR5.2: the buckets of the evaluation, in seconds. */
    static final Duration[] EVALUATION_BUCKETS = {
        Duration.ofMillis(10),
        Duration.ofMillis(30),
        Duration.ofMillis(50),
        Duration.ofMillis(100),
        Duration.ofMillis(150),
        Duration.ofMillis(300),
        Duration.ofSeconds(1)
    };

    /** U3-NFR5.3: the buckets of the wait for the lock, up to its limit of 1 s. */
    static final Duration[] LOCK_BUCKETS = {
        Duration.ofMillis(5),
        Duration.ofMillis(20),
        Duration.ofMillis(50),
        Duration.ofMillis(100),
        Duration.ofMillis(250),
        Duration.ofMillis(500),
        Duration.ofSeconds(1)
    };

    private final MeterRegistry registry;
    private final Timer lockWait;
    private final Counter expired;
    private final Counter dailyLossPauses;
    private final Map<RealGateUnavailable.Dependency, Counter> errors =
            new EnumMap<>(RealGateUnavailable.Dependency.class);
    private final Map<String, Counter> jobRuns = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> jobLastSuccess = new ConcurrentHashMap<>();
    private final Map<String, Timer> jobTimers = new ConcurrentHashMap<>();

    public RealRiskMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.lockWait = Timer.builder(LOCK_WAIT)
                .description("Wait for the lock of the wallet before a REAL buy is evaluated")
                .serviceLevelObjectives(LOCK_BUCKETS)
                .register(registry);
        this.expired = Counter.builder(EXPIRED)
                .description("Reservations of approved REAL buys that expired without the hand-over of the U4")
                .register(registry);
        this.dailyLossPauses = Counter.builder(DAILY_LOSS_PAUSES)
                .description("Bot wallets paused by the loss of the day")
                .register(registry);
        for (RealGateUnavailable.Dependency dependency : RealGateUnavailable.Dependency.values()) {
            errors.put(
                    dependency,
                    Counter.builder(ERRORS)
                            .description("Technical failures of the REAL barrier; it failed closed")
                            .tag("dependency", dependency.label())
                            .register(registry));
        }
        for (String job : JOBS) {
            jobRuns.put(
                    job,
                    Counter.builder(JOB_RUNS)
                            .description("Successful runs of a job of the REAL barrier")
                            .tag("job", job)
                            .register(registry));
            AtomicLong last = new AtomicLong();
            jobLastSuccess.put(job, last);
            Gauge.builder(JOB_LAST_SUCCESS, last, AtomicLong::get)
                    .description("Epoch second of the last successful run of a job of the REAL barrier")
                    .tag("job", job)
                    .register(registry);
            jobTimers.put(
                    job,
                    Timer.builder(JOB)
                            .description("Duration of a run of a job of the REAL barrier")
                            .tag("job", job)
                            .register(registry));
        }
    }

    /** U3-NFR5.1 and U3-NFR5.2: one evaluation, with its time after the lock. */
    public void evaluated(OrderSide side, RealVerdict verdict, Duration elapsed) {
        Counter.builder(EVALUATIONS)
                .description("REAL evaluations by side, outcome, code and detail")
                .tag("side", side.name())
                .tag("outcome", verdict.approved() ? "APPROVED" : "REJECTED")
                .tag("code", verdict.approved() ? "NONE" : verdict.code().name())
                .tag("detail", verdict.approved() ? "NONE" : verdict.detail().name())
                .register(registry)
                .increment();
        Timer.builder(EVALUATION)
                .description("Time of a REAL evaluation after the lock of the wallet")
                .tag("side", side.name())
                .serviceLevelObjectives(EVALUATION_BUCKETS)
                .register(registry)
                .record(elapsed);
    }

    public void lockWaited(Duration waited) {
        lockWait.record(waited);
    }

    public void error(RealGateUnavailable.Dependency dependency) {
        errors.get(dependency).increment();
    }

    public void reservationsExpired(int count) {
        if (count > 0) {
            expired.increment(count);
        }
    }

    public void dailyLossPause() {
        dailyLossPauses.increment();
    }

    /** U3-NFR5.8: a successful run of {@code job} that took {@code duration}, finished at {@code at}. */
    public void jobSucceeded(String job, Duration duration, Instant at) {
        requireJob(job);
        jobRuns.get(job).increment();
        jobLastSuccess.get(job).set(at.getEpochSecond());
        jobTimers.get(job).record(duration);
    }

    private static void requireJob(String job) {
        if (!JOBS.contains(job)) {
            throw new IllegalArgumentException("unknown job of the REAL barrier: " + job);
        }
    }
}
