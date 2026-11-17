package com.memetrade.realexits.infra;

import com.memetrade.realexits.domain.CancelReason;
import com.memetrade.realexits.domain.ConditionalState;
import com.memetrade.realexits.domain.ExitSide;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 261113-trading-real-bot U7 (observability-requirements.md U7-NFR5.1 to U7-NFR5.6b): the metrics of the conditional
 * exits and of the fiscal export. Every label comes from a closed enum or list, never a person, an address or a mint;
 * every counter is registered with zero at start-up for every value of its label. The gauges are read from the
 * database by the {@code exits-gauges} job of the worker every 30 s and exist only once it ran (the stopped-job alert
 * covers their absence).
 */
@Component
public class RealExitsMetrics {

    /** The closed label {@code job} (U7-NFR5.6): the three jobs of the worker and the purge of the api. */
    public static final List<String> JOBS = List.of("read", "cancel", "exits-gauges", "purge");

    /** The closed label {@code result} of the fiscal export (U7-NFR5.5). */
    public static final List<String> EXPORT_RESULTS = List.of("OK", "RATE_LIMITED", "INVALID");

    private final MeterRegistry meters;
    private final AtomicBoolean gaugesRegistered = new AtomicBoolean();
    private final Map<ConditionalState, AtomicLong> byState = new EnumMap<>(ConditionalState.class);
    private final AtomicLong cancellingOldest = new AtomicLong();
    private final AtomicLong unreadOldest = new AtomicLong();

    public RealExitsMetrics(MeterRegistry meters) {
        this.meters = meters;
        for (ExitSide side : ExitSide.values()) {
            fills(side);
        }
        for (CancelReason reason : CancelReason.values()) {
            cancels(reason);
        }
        EXPORT_RESULTS.forEach(this::exports);
        JOBS.forEach(this::runs);
        fillLag();
        for (ConditionalState state : ConditionalState.values()) {
            byState.put(state, new AtomicLong());
        }
    }

    /** U7-NFR5.2: a fill entered the ledger, {@code lagSeconds} after it was finalized on the network. */
    public void filled(ExitSide side, long lagSeconds) {
        fills(side).increment();
        fillLag().record(Math.max(0, lagSeconds));
    }

    /** U7-NFR5.3: a conditional reached CANCELLED, by the reason of its cancellation. */
    public void cancelled(CancelReason reason) {
        cancels(reason).increment();
    }

    /** U7-NFR5.5: one fiscal export with its result. */
    public void exported(String result) {
        exports(result).increment();
    }

    /** U7-NFR5.6: the heartbeat of a job, counted at the end of every run that ended well, even with nothing to do. */
    public void jobRan(String job) {
        runs(job).increment();
    }

    /** U7-NFR5.1, U7-NFR5.3, U7-NFR5.6b: the gauges read from the database by {@code exits-gauges}. */
    public void gauges(Map<ConditionalState, Long> counts, long cancellingOldestSeconds, long unreadOldestSeconds) {
        registerGauges();
        counts.forEach((state, count) -> byState.get(state).set(count));
        cancellingOldest.set(cancellingOldestSeconds);
        unreadOldest.set(unreadOldestSeconds);
    }

    private void registerGauges() {
        if (!gaugesRegistered.compareAndSet(false, true)) {
            return;
        }
        byState.forEach((state, value) -> Gauge.builder("real_conditionals", value, AtomicLong::get)
                .tag("state", state.name())
                .description("REAL conditional exits by state")
                .register(meters));
        Gauge.builder("real_conditional_cancelling_oldest_seconds", cancellingOldest, AtomicLong::get)
                .description("Age of the oldest conditional in CANCELLING")
                .register(meters);
        Gauge.builder("real_conditional_unread_oldest_seconds", unreadOldest, AtomicLong::get)
                .description("Age of the oldest successful read among the OPEN conditionals")
                .register(meters);
    }

    private Counter fills(ExitSide side) {
        return Counter.builder("real_conditional_fills")
                .tag("side", side.name())
                .description("Fills of REAL conditional exits entered in the ledger")
                .register(meters);
    }

    private Counter cancels(CancelReason reason) {
        return Counter.builder("real_conditional_cancels")
                .tag("reason", reason.name())
                .description("REAL conditional exits cancelled, by reason")
                .register(meters);
    }

    private Counter exports(String result) {
        return Counter.builder("real_fiscal_exports")
                .tag("result", result)
                .description("Fiscal CSV exports of the REAL ledger, by result")
                .register(meters);
    }

    private Counter runs(String job) {
        return Counter.builder("real_exits_job_runs")
                .tag("job", job)
                .description("Runs of the jobs of the REAL conditional exits that ended well")
                .register(meters);
    }

    private DistributionSummary fillLag() {
        return DistributionSummary.builder("real_conditional_fill_lag")
                .description("Seconds from the finalized fill on the network to its entry in the ledger")
                .baseUnit("seconds")
                .publishPercentileHistogram()
                .register(meters);
    }
}
