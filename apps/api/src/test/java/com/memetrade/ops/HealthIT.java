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
