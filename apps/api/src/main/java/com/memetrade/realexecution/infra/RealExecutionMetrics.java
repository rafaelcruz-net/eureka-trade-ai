package com.memetrade.realexecution.infra;

import com.memetrade.realexecution.api.RealFailureCode;
import com.memetrade.realexecution.api.RealOrderState;
import com.memetrade.realexecution.api.RealSide;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Metrics of the REAL execution (U4-NFR5.1 to U4-NFR5.8, observability-design.md). Every label comes from a closed enum
 * of the code: never a mint, an address, a signature nor a person. The counters the alerts read exist with zero from
 * the start, so {@code increase()} fires on the first occurrence. The gauges of the ambiguous orders and of the oldest
 * SENT order are registered only when the worker first measures them.
 */
@Component
public class RealExecutionMetrics {

    /** The jobs of the worker, label {@code job} of {@code real_execution_job_runs_total} (U4-NFR5.14). */
    public enum Job {
        QUEUE("queue"),
        DRIVER("driver"),
        CONFIRMATION("confirmation"),
        AMBIGUOUS("ambiguous"),
        SIGNING_DEADLINE("signing-deadline"),
        HEALTH("health"),
        LIQUIDATION("liquidation"),
        /** The daily purge of the api (U4-NFR3.9), the only job of this list that runs in the api. */
        PURGE("purge");

        private final String label;

        Job(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Providers, label {@code provider} (U4-NFR5.6). */
    public enum Provider {
        JUPITER,
        RPC,
        TXSIGNER
    }

    static final List<String> MODES = List.of("SIGNED", "AUTO_EXITS", "AUTO_FULL");

    private final MeterRegistry meters;
    private final AtomicLong ambiguousOpen = new AtomicLong();
    private final AtomicLong ambiguousOldestSeconds = new AtomicLong();
    private final AtomicLong sentOldestSeconds = new AtomicLong();
    private final AtomicBoolean gaugesRegistered = new AtomicBoolean();

    public RealExecutionMetrics(MeterRegistry meters) {
        this.meters = meters;
        for (Job job : Job.values()) {
            jobRuns(job);
        }
        for (Provider provider : Provider.values()) {
            upstreamFailures(provider);
        }
        Counter.builder("real_upstream_pauses")
                .description("REAL pauses UPSTREAM of a person after repeated failures of a provider")
                .register(meters);
        for (RealSide side : RealSide.values()) {
            for (String mode : MODES) {
                orders(side, mode, RealOrderState.FAILED, RealFailureCode.SIGNING_REFUSED.name());
            }
        }
    }

    /** U4-NFR5.1: one transition of an order. */
    public void transition(RealSide side, String mode, RealOrderState state, String code) {
        orders(side, mode, state, code == null ? "NONE" : code).increment();
    }

    /** U4-NFR5.1: a quote taken again before signing (BR3.2). */
    public void requoted(RealSide side, String mode) {
        orders(side, mode, RealOrderState.QUOTED, "REQUOTED").increment();
    }

    /** U4-NFR5.2: from the decision recorded to the send. */
    public void decisionToSend(String mode, Duration elapsed) {
        Timer.builder("real_order_decision_to_send")
                .description("From the REAL decision recorded to the send of its transaction")
                .tag("mode", mode)
                .serviceLevelObjectives(
                        millis(500), millis(1_000), millis(2_000), millis(3_000), millis(5_000), millis(10_000))
                .register(meters)
                .record(elapsed);
    }

    /** U4-NFR5.3: from the send to a final state or AMBIGUOUS. */
    public void sendToFinal(Duration elapsed) {
        Timer.builder("real_order_send_to_final")
                .description("From the send of a REAL order to its final state or AMBIGUOUS")
                .serviceLevelObjectives(
                        millis(2_000), millis(5_000), millis(10_000), millis(20_000), millis(40_000), millis(60_000))
                .register(meters)
                .record(elapsed);
    }

    /** U4-NFR5.8: from the decision recorded to the consumer. */
    public void queueWait(Duration elapsed) {
        Timer.builder("real_decision_queue_wait")
                .description("From the REAL decision recorded to its consumption")
                .serviceLevelObjectives(millis(200), millis(500), millis(1_000), millis(2_000), millis(5_000))
                .register(meters)
                .record(elapsed);
    }

    /** U4-NFR5.6: a technical failure of a provider. */
    public void upstreamFailure(Provider provider) {
        upstreamFailures(provider).increment();
    }

    public void upstreamPause() {
        meters.counter("real_upstream_pauses").increment();
    }

    /** U4-NFR5.7: a job finished a run without failure. */
    public void jobRun(Job job) {
        jobRuns(job).increment();
    }

    /** U4-NFR5.4, U4-NFR3.5: the gauges of the worker, refreshed by the ambiguous job. */
    public void gauges(long ambiguous, long oldestAmbiguousSeconds, long oldestSentSeconds) {
        if (gaugesRegistered.compareAndSet(false, true)) {
            Gauge.builder("real_orders_ambiguous_open", ambiguousOpen, AtomicLong::get)
                    .description("REAL orders AMBIGUOUS now")
                    .register(meters);
            Gauge.builder("real_orders_ambiguous_oldest_seconds", ambiguousOldestSeconds, AtomicLong::get)
                    .description("Age of the oldest AMBIGUOUS REAL order")
                    .register(meters);
            Gauge.builder("real_orders_sent_oldest_seconds", sentOldestSeconds, AtomicLong::get)
                    .description("Age of the oldest SENT REAL order")
                    .register(meters);
        }
        ambiguousOpen.set(ambiguous);
        ambiguousOldestSeconds.set(oldestAmbiguousSeconds);
        sentOldestSeconds.set(oldestSentSeconds);
    }

    private Counter orders(RealSide side, String mode, RealOrderState state, String code) {
        return Counter.builder("real_orders")
                .description("Transitions of the REAL orders by side, mode, state and code")
                .tag("side", side.name())
                .tag("mode", mode)
                .tag("state", state.name())
                .tag("code", code)
                .register(meters);
    }

    private Counter upstreamFailures(Provider provider) {
        return Counter.builder("real_upstream_failures")
                .description("Technical failures of the providers of the REAL execution")
                .tag("provider", provider.name())
                .register(meters);
    }

    private Counter jobRuns(Job job) {
        return Counter.builder("real_execution_job_runs")
                .description("Runs of the jobs of the REAL execution finished without failure")
                .tag("job", job.label().toLowerCase(Locale.ROOT))
                .register(meters);
    }

    private static Duration millis(long value) {
        return Duration.ofMillis(value);
    }
}
