package com.memetrade.pnl;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.testsupport.AbstractPaperRealIT;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;

/**
 * 261121-paper-real-unico U3, Etapa 12 (test after the layer; NFR3.11, NFR3.15, NFR3.16, NFR8.4): the three counters of
 * the U3 are exported by the api in {@code /actuator/prometheus} (the management port) with their exported names and
 * labels, registered from the start.
 */
@Tag("u3-pnl-views")
class PnlViewsPrometheusIT extends AbstractPaperRealIT {

    @Autowired
    private TestRestTemplate rest;

    @LocalManagementPort
    private int managementPort;

    @Test
    void theThreeCountersAreExportedFromTheStart() {
        String metrics = rest.getForEntity("http://127.0.0.1:" + managementPort + "/actuator/prometheus", String.class)
                .getBody();

        assertThat(metrics)
                .contains("pnl_compare_template_mismatches_total")
                .contains("pnl_compare_side_unavailable_total{")
                .contains("market=\"REAL\"")
                .contains("cause=\"TIMEOUT\"")
                .contains("pnl_read_audit_failures_total{")
                .contains("action=\"TENANT_MISMATCH\"")
                .contains("action=\"TEMPLATE_MISMATCH\"");
    }
}
