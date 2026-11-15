package com.memetrade.riskcontrol.real.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.memetrade.riskcontrol.api.OrderSide;
import com.memetrade.riskcontrol.api.RealGateUnavailable;
import com.memetrade.riskcontrol.api.RealRejectionCode;
import com.memetrade.riskcontrol.api.RealRejectionDetail;
import com.memetrade.riskcontrol.real.domain.RealVerdict;
import com.memetrade.shared.config.ProcessRole;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Step 10 of the U3 (U3-NFR5.1 to U3-NFR5.8): names, labels and buckets of the metrics of the barrier, the gauges and
 * counters that exist at zero, and no mint, address, tenant nor person in any label.
 */
class RealRiskMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RealRiskMetrics metrics = new RealRiskMetrics(registry);

    private static RealVerdict refusal() {
        return new RealVerdict(
                false,
                RealRejectionCode.REAL_CAP_EXCEEDED,
                RealRejectionDetail.CAP,
                Map.of("capLamports", 1L),
                Optional.of("Mint1111111111111111111111111111111111111"),
                false,
                Optional.empty());
    }

    @Test
    void anEvaluationIsCountedBySideOutcomeCodeAndDetail() {
        metrics.evaluated(OrderSide.BUY, refusal(), Duration.ofMillis(40));
        metrics.evaluated(
                OrderSide.SELL,
                new RealVerdict(true, null, null, Map.of(), Optional.empty(), false, Optional.empty()),
                Duration.ofMillis(5));

        assertThat(registry.get("real_risk_evaluations")
                        .tags("side", "BUY", "outcome", "REJECTED", "code", "REAL_CAP_EXCEEDED", "detail", "CAP")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get("real_risk_evaluations")
                        .tags("side", "SELL", "outcome", "APPROVED", "code", "NONE", "detail", "NONE")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void theEvaluationHasTheBucketsOfTheRequirement() {
        metrics.evaluated(OrderSide.BUY, refusal(), Duration.ofMillis(120));

        CountAtBucket[] buckets = registry.get("real_risk_evaluation")
                .tag("side", "BUY")
                .timer()
                .takeSnapshot()
                .histogramCounts();
        assertThat(Arrays.stream(buckets)
                        .map(bucket -> (long) bucket.bucket(java.util.concurrent.TimeUnit.MILLISECONDS)))
                .containsExactly(10L, 30L, 50L, 100L, 150L, 300L, 1000L);
        assertThat(Arrays.stream(buckets)
                        .filter(bucket -> bucket.bucket(java.util.concurrent.TimeUnit.MILLISECONDS) == 150)
                        .findFirst()
                        .orElseThrow()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void theErrorsOfEveryDependencyAndTheJobsExistAtZero() {
        for (RealGateUnavailable.Dependency dependency : RealGateUnavailable.Dependency.values()) {
            assertThat(registry.get("real_risk_errors")
                            .tag("dependency", dependency.label())
                            .counter()
                            .count())
                    .isZero();
        }
        for (String job : RealRiskMetrics.JOBS) {
            assertThat(registry.get("real_risk_job_runs")
                            .tag("job", job)
                            .counter()
                            .count())
                    .isZero();
            assertThat(registry.get("real_risk_job_last_success_seconds")
                            .tag("job", job)
                            .gauge()
                            .value())
                    .isZero();
        }
        metrics.error(RealGateUnavailable.Dependency.LOCK);
        assertThat(registry.get("real_risk_errors")
                        .tag("dependency", "lock")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void aSuccessfulJobMovesItsCounterHeartbeatAndTimerAndAnUnknownJobIsRefused() {
        Instant at = Instant.parse("2026-11-14T00:05:00Z");

        metrics.jobSucceeded("day", Duration.ofMillis(800), at);

        assertThat(registry.get("real_risk_job_runs")
                        .tag("job", "day")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get("real_risk_job_last_success_seconds")
                        .tag("job", "day")
                        .gauge()
                        .value())
                .isEqualTo((double) at.getEpochSecond());
        assertThat(registry.get("real_risk_job").tag("job", "day").timer().count())
                .isEqualTo(1);
        assertThatThrownBy(() -> metrics.jobSucceeded("other", Duration.ZERO, at))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theLockWaitTheExpiriesAndThePausesAreCounted() {
        metrics.lockWaited(Duration.ofMillis(30));
        metrics.reservationsExpired(0);
        metrics.reservationsExpired(3);
        metrics.dailyLossPause();

        assertThat(registry.get("real_risk_lock_wait").timer().count()).isEqualTo(1);
        assertThat(registry.get("real_risk_reservations_expired").counter().count())
                .isEqualTo(3);
        assertThat(registry.get("real_risk_daily_loss_pauses").counter().count())
                .isEqualTo(1);
    }

    @Test
    void noLabelEverCarriesAMintAnAddressATenantOrAPerson() {
        metrics.evaluated(OrderSide.BUY, refusal(), Duration.ofMillis(40));
        metrics.jobSucceeded("purge", Duration.ofSeconds(1), Instant.EPOCH);

        // "le" is the bucket bound the registry adds to the histogram of a timer.
        Set<String> allowed = Set.of("side", "outcome", "code", "detail", "dependency", "job", "state", "le");
        for (Meter meter : registry.getMeters()) {
            meter.getId().getTags().forEach(tag -> {
                assertThat(allowed).as(meter.getId().getName()).contains(tag.getKey());
                assertThat(tag.getValue()).doesNotMatch("[1-9A-HJ-NP-Za-km-z]{32,44}");
            });
        }
    }

    @Test
    void theReservationGaugeExistsOnlyInTheWorkerAndKeepsItsValuesWhenTheReadFails() {
        JdbcRealRiskStore store = mock(JdbcRealRiskStore.class);
        when(store.reservationsByState())
                .thenReturn(Map.of("OPEN", 2L, "HANDED_OVER", 5L, "EXPIRED", 1L))
                .thenThrow(new DataAccessResourceFailureException("down"));
        RealRiskReservationGauge gauge = new RealRiskReservationGauge(store, registry);

        gauge.refresh();
        gauge.refresh();

        assertThat(registry.get("real_risk_reservations")
                        .tag("state", "OPEN")
                        .gauge()
                        .value())
                .isEqualTo(2);
        assertThat(registry.get("real_risk_reservations")
                        .tag("state", "HANDED_OVER")
                        .gauge()
                        .value())
                .isEqualTo(5);
        assertThat(RealRiskReservationGauge.class
                        .getAnnotation(ProcessRole.class)
                        .value())
                .isEqualTo(ProcessRole.Kind.WORKER);
        assertThat(RealRiskSchedule.class.getAnnotation(ProcessRole.class).value())
                .isEqualTo(ProcessRole.Kind.WORKER);
        assertThat(RealRiskPurgeJob.class.getAnnotation(ProcessRole.class).value())
                .isEqualTo(ProcessRole.Kind.API);
    }
}
