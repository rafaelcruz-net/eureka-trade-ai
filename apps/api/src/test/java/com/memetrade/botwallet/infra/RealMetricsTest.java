package com.memetrade.botwallet.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Step 20, written after the code (observability-design.md, NFR5.16): the counters of deposits and withdrawals, the
 * time to confirm and the unknown deposits above the threshold, and no metric with an address, tenant or user label.
 */
class RealMetricsTest {

    private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
    private final RealMetrics metrics = new RealMetrics(meters, jdbc, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void depositsCountBySourceAndUnknownOnesAboveTheThresholdAreFlagged() {
        metrics.deposit(true, 5L);
        metrics.deposit(false, 2_000_000L);
        metrics.deposit(false, 10L);

        assertThat(meters.counter("realwallet_deposits", "source", "known").count())
                .isEqualTo(1);
        assertThat(meters.counter("realwallet_deposits", "source", "unknown").count())
                .isEqualTo(2);
        assertThat(meters.counter("realwallet_unknown_deposit_over_threshold").count())
                .isEqualTo(1);
        assertThat(meters.summary("realwallet_unknown_deposit_lamports").count())
                .isEqualTo(2);
    }

    @Test
    void aConfirmedWithdrawalRecordsItsTimeToConfirm() {
        metrics.withdrawalState("SENT", NOW.minusSeconds(30));
        metrics.withdrawalState("CONFIRMED", NOW.minusSeconds(30));
        metrics.reconciliationMark();

        assertThat(meters.counter("realwallet_withdrawals", "state", "CONFIRMED")
                        .count())
                .isEqualTo(1);
        assertThat(meters.timer("realwallet_withdrawal").totalTime(java.util.concurrent.TimeUnit.SECONDS))
                .isEqualTo(30);
        assertThat(meters.counter("realwallet_reconciliation_marks").count()).isEqualTo(1);
    }

    @Test
    void theGaugesReadTheDatabaseAndNoMetricCarriesAnIdentityLabel() {
        when(jdbc.sql(anyString()).query(Long.class).single()).thenReturn(42L);

        assertThat(meters.get("realwallet_balance_lamports_total").gauge().value())
                .isEqualTo(42);
        assertThat(meters.get("real_bot_wallets_active").gauge().value()).isEqualTo(42);
        metrics.deposit(false, 1L);
        metrics.withdrawalState("FAILED", NOW);
        for (Meter meter : meters.getMeters()) {
            assertThat(meter.getId().getTags())
                    .noneMatch(tag -> tag.getKey().matches("(?i).*(address|tenant|user|wallet).*"));
        }
    }
}
