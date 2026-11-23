package com.memetrade.pnl;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.testsupport.AbstractPaperRealIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;

/**
 * 261121-paper-real-unico U2, Etapa 22 (written after the layer; NFR2.11 to NFR2.13, NFR1.23, NFR3.16, NFR1.6;
 * infrastructure-specification.md "Métricas do pool"): the five metrics of the U2 are exported by the api in
 * {@code /actuator/prometheus} with their exported names, registered from the start; the counter of the blocks of C4
 * carries the label {@code cause}; and the pool {@code pnl-mismatch-audit} publishes its {@code hikaricp_*} metrics.
 */
@Tag("u2-real-bot-pnl")
class U2MetricsExportIT extends AbstractPaperRealIT {

    @Autowired
    private TestRestTemplate rest;

    @LocalManagementPort
    private int managementPort;

    @Test
    void theFiveMetricsTheCauseAndThePoolAreExported() {
        String metrics = rest.getForEntity("http://127.0.0.1:" + managementPort + "/actuator/prometheus", String.class)
                .getBody();

        assertThat(metrics)
                .contains("pnl_real_reconciliation_mismatches_total{")
                .contains("check=\"UNKNOWN_BOT\"")
                .contains("pnl_real_reconciliation_audit_failures_total")
                .contains("pnl_real_attribution_read_seconds_count{")
                .contains("path=\"PNL\"")
                .contains("ledger_real_slot_missing_total")
                .contains("ledger_real_slot_conflicts_total{")
                .contains("pnl_mode_block_unavailable_total{")
                .contains("cause=\"RECONCILIATION_MISMATCH\"")
                .contains("hikaricp_connections_pending{pool=\"pnl-mismatch-audit\"");
    }
}
