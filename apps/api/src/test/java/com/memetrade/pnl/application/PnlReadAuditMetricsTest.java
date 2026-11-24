package com.memetrade.pnl.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U3, Etapa 12 (test after the layer; NFR3.16): {@code pnl.read_audit.failures{action}} has its
 * 2 series registered with 0, and a failed write counts only the series of its action.
 */
class PnlReadAuditMetricsTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final PnlReadAuditMetrics metrics = new PnlReadAuditMetrics(meters);

    @Test
    void twoSeriesAreRegisteredWithZero() {
        assertThat(meters.find(PnlReadAuditMetrics.FAILURES).counters())
                .hasSize(2)
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
    }

    @Test
    void aFailedWriteCountsOnlyItsAction() {
        metrics.failure(ReadAuditAction.TENANT_MISMATCH);

        assertThat(failures("TENANT_MISMATCH")).isEqualTo(1);
        assertThat(failures("TEMPLATE_MISMATCH")).isZero();
    }

    @Test
    void eachActionWritesItsOwnActionOfTheTrail() {
        assertThat(ReadAuditAction.TENANT_MISMATCH.auditAction()).isEqualTo("TENANT_MISMATCH");
        assertThat(ReadAuditAction.TEMPLATE_MISMATCH.auditAction()).isEqualTo("PNL_COMPARE_TEMPLATE_MISMATCH");
    }

    private double failures(String action) {
        return meters.find(PnlReadAuditMetrics.FAILURES)
                .tag("action", action)
                .counter()
                .count();
    }
}
