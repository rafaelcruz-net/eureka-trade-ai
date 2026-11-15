package com.memetrade.realexecution.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.realexecution.api.RealOrderState;
import com.memetrade.realexecution.api.RealSide;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 15 of the U4, written after the code (U4-NFR5.1 to U4-NFR5.8, U4-NFR5.14, U4-NFR3.5): the metrics of the REAL
 * execution have closed labels (never a mint, an address, a signature nor a person), the counters the alerts read are
 * registered with zero at the start (so {@code increase()} fires on the first occurrence and {@code absent()} never
 * fires on a healthy process), the timers carry the buckets of the requirements and the age gauges appear only when the
 * worker first measures them.
 */
@Tag("u4-real-execution")
class RealExecutionMetricsTest {

    /** The labels of the U4 plus {@code le}, the bucket of the histograms (a fixed list of durations). */
    private static final Set<String> CLOSED_LABELS = Set.of("side", "mode", "state", "code", "provider", "job", "le");

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final RealExecutionMetrics metrics = new RealExecutionMetrics(meters);

    @Test
    void theCountersOfTheAlertsExistWithZeroAtTheStart() {
        for (RealExecutionMetrics.Job job : RealExecutionMetrics.Job.values()) {
            assertThat(meters.counter("real_execution_job_runs", "job", job.label())
                            .count())
                    .as(job.label())
                    .isZero();
        }
        for (RealExecutionMetrics.Provider provider : RealExecutionMetrics.Provider.values()) {
            assertThat(meters.counter("real_upstream_failures", "provider", provider.name())
                            .count())
                    .isZero();
        }
        assertThat(meters.find("real_upstream_pauses").counter()).isNotNull();
        assertThat(meters.find("real_orders").tag("code", "SIGNING_REFUSED").counters())
                .hasSize(RealSide.values().length * 3);
    }

    @Test
    void theJobsAreTheSevenOfTheWorkerAndThePurgeOfTheApi() {
        assertThat(meters.find("real_execution_job_runs").counters().stream()
                        .map(counter -> counter.getId().getTag("job"))
                        .collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(
                        "queue",
                        "driver",
                        "confirmation",
                        "ambiguous",
                        "signing-deadline",
                        "health",
                        "liquidation",
                        "purge");
    }

    @Test
    void aTransitionAndARunCountOnceWithTheirClosedLabels() {
        metrics.transition(RealSide.BUY, "AUTO_FULL", RealOrderState.CONFIRMED, "NONE");
        metrics.requoted(RealSide.SELL, "SIGNED");
        metrics.jobRun(RealExecutionMetrics.Job.QUEUE);
        metrics.upstreamFailure(RealExecutionMetrics.Provider.JUPITER);
        metrics.upstreamPause();

        assertThat(meters.counter(
                                "real_orders", "side", "BUY", "mode", "AUTO_FULL", "state", "CONFIRMED", "code", "NONE")
                        .count())
                .isEqualTo(1);
        assertThat(meters.counter(
                                "real_orders", "side", "SELL", "mode", "SIGNED", "state", "QUOTED", "code", "REQUOTED")
                        .count())
                .isEqualTo(1);
        assertThat(meters.counter("real_execution_job_runs", "job", "queue").count())
                .isEqualTo(1);
        assertThat(meters.counter("real_upstream_failures", "provider", "JUPITER")
                        .count())
                .isEqualTo(1);
        assertThat(meters.counter("real_upstream_pauses").count()).isEqualTo(1);
    }

    @Test
    void theTimersCarryTheBucketsOfTheRequirements() {
        metrics.decisionToSend("AUTO_FULL", Duration.ofMillis(2_500));
        metrics.sendToFinal(Duration.ofSeconds(12));
        metrics.queueWait(Duration.ofMillis(150));

        assertThat(meters.find("real_order_decision_to_send")
                        .tag("mode", "AUTO_FULL")
                        .timer())
                .isNotNull()
                .satisfies(timer -> {
                    assertThat(timer.count()).isEqualTo(1);
                    assertThat(timer.takeSnapshot().histogramCounts()).hasSize(6);
                });
        assertThat(meters.find("real_order_send_to_final")
                        .timer()
                        .takeSnapshot()
                        .histogramCounts())
                .hasSize(6);
        assertThat(meters.find("real_decision_queue_wait")
                        .timer()
                        .takeSnapshot()
                        .histogramCounts())
                .hasSize(5);
    }

    @Test
    void theAgeGaugesAppearOnlyAfterTheFirstMeasureOfTheWorker() {
        assertThat(meters.find("real_orders_ambiguous_open").gauge()).isNull();

        metrics.gauges(2, 310, 80);
        metrics.gauges(1, 20, 5);

        assertThat(meters.find("real_orders_ambiguous_open").gauge().value()).isEqualTo(1);
        assertThat(meters.find("real_orders_ambiguous_oldest_seconds").gauge().value())
                .isEqualTo(20);
        assertThat(meters.find("real_orders_sent_oldest_seconds").gauge().value())
                .isEqualTo(5);
        assertThat(meters.find("real_orders_ambiguous_open").gauges()).hasSize(1);
    }

    @Test
    void noMetricCarriesAnOpenLabel() {
        metrics.transition(RealSide.BUY, "SIGNED", RealOrderState.FAILED, "SLIPPAGE_EXCEEDED");
        metrics.decisionToSend("SIGNED", Duration.ofSeconds(1));
        metrics.gauges(0, 0, 0);

        for (Meter meter : meters.getMeters()) {
            assertThat(meter.getId().getTags()).as(meter.getId().getName()).allSatisfy(tag -> assertThat(CLOSED_LABELS)
                    .contains(tag.getKey()));
        }
    }
}
