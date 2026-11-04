package com.memetrade.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.audit.api.AuditActions;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.audit.infra.AuditOutboxRelay;
import com.memetrade.testsupport.AbstractPostgresIT;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

class HealthIT extends AbstractPostgresIT {

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private AuditEventPublisher publisher;

    @Autowired
    private AuditOutboxRelay relay;

    @Autowired
    private TransactionTemplate tx;

    @Test
    void readinessIsUpWithTheDatabaseAndMigrationsApplied() throws Exception {
        ResponseEntity<String> response = get("/actuator/health/readiness");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode body = json.readTree(response.getBody());
        assertThat(body.get("status").asText()).isEqualTo("UP");
        assertThat(body.at("/components/migrations/status").asText()).isEqualTo("UP");
        assertThat(body.at("/components/db/status").asText()).isEqualTo("UP");
    }

    @Test
    void aLaggingRelayMakesHealthDegradedButStillHttp200AndReadinessUp() throws Exception {
        tx.executeWithoutResult(status -> publisher.publish(AuditEventCommand.anonymous(
                AuditActions.LOGIN_REJECTED,
                "anonymous",
                null,
                UUID.randomUUID().toString(),
                clock.instant(),
                Map.of())));
        clock.advance(Duration.ofSeconds(90));

        ResponseEntity<String> health = get("/actuator/health");

        assertThat(health.getStatusCode().value()).isEqualTo(200);
        JsonNode body = json.readTree(health.getBody());
        assertThat(body.at("/components/auditRelay/status").asText()).isEqualTo("DEGRADED");
        assertThat(get("/actuator/health/readiness").getStatusCode().value()).isEqualTo(200);
        relay.relayBatch();
    }

    @Test
    void prometheusExposesTheU1Metrics() {
        ResponseEntity<String> metrics = get("/actuator/prometheus");

        assertThat(metrics.getStatusCode().value()).isEqualTo(200);
        assertThat(metrics.getBody())
                .contains("audit_outbox_pending")
                .contains("market_source_status")
                .contains("market_source_last_collection_age_seconds");
    }

    /** U4 (reliability-design.md, Q1): every source FAILED is DEGRADED with HTTP 200, never DOWN; readiness stays UP. */
    @Test
    @org.junit.jupiter.api.Tag("u4")
    void withEverySourceFailedTheHealthIsStill200WithMarketSourcesDegraded() throws Exception {
        java.util.List<java.util.Map<String, Object>> before =
                jdbc.sql("select source_id, status from data_source").query().listOfRows();
        try {
            jdbc.sql("update data_source set status = 'FAILED'").update();

            ResponseEntity<String> health = get("/actuator/health");

            assertThat(health.getStatusCode().value()).isEqualTo(200);
            JsonNode sources = json.readTree(health.getBody()).at("/components/marketSources");
            assertThat(sources.get("status").asText()).isEqualTo("DEGRADED");
            assertThat(sources.at("/details/sources/sim-a/status").asText()).isEqualTo("FAILED");
            assertThat(get("/actuator/health/readiness").getStatusCode().value())
                    .isEqualTo(200);
        } finally {
            for (java.util.Map<String, Object> row : before) {
                jdbc.sql("update data_source set status = :s where source_id = :id")
                        .param("s", row.get("status"))
                        .param("id", row.get("source_id"))
                        .update();
            }
        }
    }

    /** U4: with a source OK the health is UP with lastUpdatedAt, which scripts/verify.sh waits for. */
    @Test
    @org.junit.jupiter.api.Tag("u4")
    void afterACollectionTheSourcesAreUpWithTheLastUpdateAndTheRecalculationHasItsComponent() throws Exception {
        collector.collectNow("sim-a");

        JsonNode body = json.readTree(get("/actuator/health").getBody());

        assertThat(body.at("/components/marketSources/status").asText()).isEqualTo("UP");
        assertThat(body.at("/components/marketSources/details/lastUpdatedAt").asText())
                .endsWith("Z");
        assertThat(body.at("/components/riskRecalculation/status").isMissingNode())
                .isFalse();
        assertThat(body.at("/components/marketSource").isMissingNode())
                .as("replaced by marketSources")
                .isTrue();
        assertThat(get("/actuator/prometheus").getBody())
                .contains("market_source_status{source=\"sim-a\"}")
                .contains("market_collection_total");
    }

    @Autowired
    private com.memetrade.marketdata.infra.MarketCollectorJob collector;

    @Test
    void theProbeExitsZeroAgainstTheRealReadinessEndpoint() {
        assertThat(HealthProbe.probe(
                        java.net.URI.create("http://127.0.0.1:" + managementPort + "/actuator/health/readiness"),
                        Duration.ofSeconds(3)))
                .isZero();
    }

    private ResponseEntity<String> get(String path) {
        return rest.getForEntity("http://127.0.0.1:" + managementPort + path, String.class);
    }
}
