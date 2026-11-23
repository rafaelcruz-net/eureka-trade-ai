package com.memetrade.pnl.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.pnl.api.CostPath;
import com.memetrade.pnl.real.ReconciliationResult.Check;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 261121-paper-real-unico U2, Etapa 22 (written after the layer; NFR1.23, NFR2.11, NFR3.16, observability-design.md):
 * every series is registered with 0 at start-up; a mismatch in FEES counts {@code check="FEES"} once and the other 7
 * stay at 0; a read of the P&L path past its budget counts in {@code outcome="TIMEOUT"}; the worker registers only the
 * timer (the counters of the reconciliation belong to the api). No label carries data of a person.
 */
class PnlRealMetricsTest {

    @Test
    void everySeriesStartsAtZeroInTheApi() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new PnlRealMetrics(registry, new MockEnvironment());

        assertThat(registry.find(PnlRealMetrics.MISMATCHES).counters()).hasSize(8);
        assertThat(registry.find(PnlRealMetrics.MISMATCHES).counters())
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
        assertThat(registry.find(PnlRealMetrics.AUDIT_FAILURES).counter().count())
                .isZero();
        assertThat(registry.find("pnl.real.attribution.read").timers()).hasSize(12);
    }

    @Test
    void aMismatchInFeesCountsOnlyItsCheck() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PnlRealMetrics metrics = new PnlRealMetrics(registry);

        metrics.mismatch(List.of(Check.FEES));

        assertThat(registry.counter(PnlRealMetrics.MISMATCHES, "check", "FEES").count())
                .isEqualTo(1);
        assertThat(registry.find(PnlRealMetrics.MISMATCHES).counters().stream()
                        .mapToDouble(io.micrometer.core.instrument.Counter::count)
                        .sum())
                .isEqualTo(1);
    }

    @Test
    void aTimeoutOfThePnlPathCountsAsTimeoutNeverOk() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PnlRealMetrics metrics = new PnlRealMetrics(registry);

        metrics.attributionRead(CostPath.PNL, PnlRealMetrics.Outcome.TIMEOUT, Duration.ofMillis(501));

        assertThat(registry.timer("pnl.real.attribution.read", "path", "PNL", "outcome", "TIMEOUT")
                        .count())
                .isEqualTo(1);
        assertThat(registry.timer("pnl.real.attribution.read", "path", "PNL", "outcome", "OK")
                        .count())
                .isZero();
    }

    @Test
    void aFailedAuditWriteCountsOnce() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PnlRealMetrics metrics = new PnlRealMetrics(registry);

        metrics.auditFailure();

        assertThat(registry.counter(PnlRealMetrics.AUDIT_FAILURES).count()).isEqualTo(1);
    }

    @Test
    void theWorkerRegistersOnlyTheTimerAndNoLabelCarriesAPerson() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new PnlRealMetrics(registry, new MockEnvironment().withProperty("memetrade.process", "worker"));

        assertThat(registry.find(PnlRealMetrics.MISMATCHES).counters()).isEmpty();
        assertThat(registry.find(PnlRealMetrics.AUDIT_FAILURES).counters()).isEmpty();
        for (Meter meter : registry.getMeters()) {
            // "le" is the bucket of the fixed limits of the timer, never data.
            assertThat(meter.getId().getTags()).allSatisfy(tag -> assertThat(List.of("path", "outcome", "check", "le"))
                    .contains(tag.getKey()));
        }
    }
}
