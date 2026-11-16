package com.memetrade.botwallet.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.botwallet.domain.BlockedReason;
import com.memetrade.botwallet.domain.ReleaseState;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 14 of the U5, written after the code (U5-NFR5.1 to U5-NFR5.6): the metrics of the operations of the REAL have
 * closed labels, every counter exists with zero at start-up (so {@code increase()} and {@code absent()} work from the
 * first scrape), the gauges carry what the {@code ops-gauges} job read, and no meter has a label with a person, a mint or
 * an address.
 */
@Tag("u5-real-operations")
class RealOpsMetricsTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final RealOpsMetrics metrics = new RealOpsMetrics(meters);

    @Test
    void everyCounterExistsWithZeroAtStartUp() {
        for (String job : RealOpsMetrics.JOBS) {
            assertThat(meters.get("real_ops_job_runs").tag("job", job).counter().count())
                    .isZero();
            assertThat(meters.get("real_ops_job").tag("job", job).timer().count())
                    .isZero();
        }
        for (String result : RealOpsMetrics.RESULTS) {
            assertThat(meters.get("real_reconciliations")
                            .tag("result", result)
                            .counter()
                            .count())
                    .isZero();
        }
        for (BlockedReason reason : BlockedReason.values()) {
            assertThat(meters.get("real_release_blocks")
                            .tag("reason", reason.name())
                            .counter()
                            .count())
                    .isZero();
        }
    }

    @Test
    void theHeartbeatTheReconciliationAndTheBlocksCount() {
        metrics.jobRan("gates", Duration.ofMillis(20));
        metrics.reconciled("CLEAN", Duration.ofMillis(700));
        metrics.blocked(BlockedReason.values()[0]);

        assertThat(meters.get("real_ops_job_runs").tag("job", "gates").counter().count())
                .isEqualTo(1.0);
        assertThat(meters.get("real_reconciliations")
                        .tag("result", "CLEAN")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(meters.get("real_reconciliation").timer().count()).isEqualTo(1);
        assertThat(meters.get("real_release_blocks")
                        .tag("reason", BlockedReason.values()[0].name())
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void theGaugesCarryWhatTheJobRead() {
        Map<ReleaseState, Long> releases = new EnumMap<>(ReleaseState.class);
        for (ReleaseState state : ReleaseState.values()) {
            releases.put(state, 0L);
        }
        releases.put(ReleaseState.ACTIVE, 3L);

        metrics.gauges(
                new RealOpsMetrics.GaugeValues(releases, 4000, 2, 1, true, 5, "RESTORE", Map.of("LOWER_CAP", 2L), 950));

        assertThat(meters.get("real_releases").tag("state", "ACTIVE").gauge().value())
                .isEqualTo(3.0);
        assertThat(meters.get("real_release_blocked_oldest_seconds").gauge().value())
                .isEqualTo(4000.0);
        assertThat(meters.get("real_divergences_open").gauge().value()).isEqualTo(2.0);
        assertThat(meters.get("real_reconciliation_deferred_over_2h").gauge().value())
                .isEqualTo(1.0);
        assertThat(meters.get("real_kill_switch_active").gauge().value()).isEqualTo(1.0);
        assertThat(meters.get("real_kill_switch_unsent_orders").gauge().value()).isEqualTo(5.0);
        assertThat(meters.get("real_restore_pause_active")
                        .tag("phase", "RESTORE")
                        .gauge()
                        .value())
                .isEqualTo(1.0);
        assertThat(meters.get("real_restore_pause_active")
                        .tag("phase", "CHECK_PENDING")
                        .gauge()
                        .value())
                .isEqualTo(0.0);
        assertThat(meters.get("real_pending_txsigner_commands")
                        .tag("kind", "LOWER_CAP")
                        .gauge()
                        .value())
                .isEqualTo(2.0);
        assertThat(meters.get("real_pending_txsigner_commands")
                        .tag("kind", "DEACTIVATE")
                        .gauge()
                        .value())
                .isEqualTo(0.0);
        assertThat(meters.get("real_pending_txsigner_oldest_seconds").gauge().value())
                .isEqualTo(950.0);
    }

    @Test
    void aSecondReadingReplacesTheFirstAndAnEndedRestoreGoesBackToZero() {
        Map<ReleaseState, Long> releases = new EnumMap<>(ReleaseState.class);
        for (ReleaseState state : ReleaseState.values()) {
            releases.put(state, 0L);
        }
        metrics.gauges(new RealOpsMetrics.GaugeValues(releases, 0, 1, 0, true, 1, "CHECK_PENDING", Map.of(), 0));

        metrics.gauges(new RealOpsMetrics.GaugeValues(releases, 0, 0, 0, false, 0, null, Map.of(), 0));

        assertThat(meters.get("real_restore_pause_active")
                        .tag("phase", "CHECK_PENDING")
                        .gauge()
                        .value())
                .isEqualTo(0.0);
        assertThat(meters.get("real_kill_switch_active").gauge().value()).isEqualTo(0.0);
        assertThat(meters.get("real_divergences_open").gauge().value()).isZero();
    }

    @Test
    void everyLabelIsClosedAndNoneNamesAPersonAMintOrAnAddress() {
        metrics.gauges(new RealOpsMetrics.GaugeValues(
                new EnumMap<>(Map.of(ReleaseState.ACTIVE, 1L)), 0, 0, 0, false, 0, null, Map.of(), 0));
        Set<String> keys = meters.getMeters().stream()
                .map(Meter::getId)
                .flatMap(id -> id.getTags().stream())
                .map(tag -> tag.getKey())
                .collect(Collectors.toSet());

        // "le" is the bucket of the histogram of the reconciliation (its SLO buckets), not a label of the code.
        assertThat(keys).isSubsetOf("job", "result", "reason", "state", "phase", "kind", "le");
    }
}
