package com.memetrade.privacy.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.privacy.domain.request.DataRequestOrigin;
import com.memetrade.privacy.domain.request.DataRequestType;
import com.memetrade.privacy.domain.request.VerificationMessage;
import com.memetrade.shared.health.HealthStatuses;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

/**
 * Passo 6 of u4-data-requests (after the implementation; NFR8.1, NFR8.2, NFR2.3): the gauges read the cached counts,
 * the counter has closed labels only, the health {@code dataRequests} is DEGRADED while an open request is overdue, and
 * the verification message is unique per request with its domain.
 */
@Tag("u4-data-requests")
class DataRequestMetricsTest {

    private static final Instant NOW = Instant.parse("2026-11-10T09:00:00Z");

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final DataRequestMetrics metrics = new DataRequestMetrics(meters);
    private final DataRequestsHealthIndicator health = new DataRequestsHealthIndicator(metrics);

    @Test
    void beforeTheFirstRefreshEverythingIsZeroAndTheHealthIsUp() {
        assertThat(meters.get("memetrade.data.requests.overdue").gauge().value())
                .isZero();
        assertThat(metrics.refreshedAt()).isEmpty();
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void aRefreshFeedsTheGaugesAndTheHealthFollowsTheOverdueCount() {
        metrics.refreshed(
                new DataRequestMetrics.Counts(Map.of(DataRequestType.ACCESS, 3L, DataRequestType.OTHER, 1L), 2, 1),
                NOW);

        assertThat(meters.get("memetrade.data.requests.open")
                        .tag("type", "ACCESS")
                        .gauge()
                        .value())
                .isEqualTo(3.0);
        assertThat(meters.get("memetrade.data.requests.open")
                        .tag("type", "DELETION")
                        .gauge()
                        .value())
                .isZero();
        assertThat(meters.get("memetrade.data.requests.overdue").gauge().value())
                .isEqualTo(2.0);
        assertThat(meters.get("memetrade.data.requests.due.soon").gauge().value())
                .isEqualTo(1.0);
        assertThat(health.health().getStatus()).isEqualTo(HealthStatuses.DEGRADED);
        assertThat(health.health().getDetails())
                .containsEntry("overdue", 2L)
                .containsEntry("refreshedAt", NOW.toString());

        metrics.refreshed(new DataRequestMetrics.Counts(Map.of(), 0, 0), NOW.plusSeconds(60));
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
        assertThat(metrics.open(DataRequestType.ACCESS)).isZero();
    }

    @Test
    void theCreatedCounterCountsByTypeAndOrigin() {
        metrics.requestCreated(DataRequestType.CORRECTION, DataRequestOrigin.EMAIL);
        metrics.requestCreated(DataRequestType.CORRECTION, DataRequestOrigin.EMAIL);

        assertThat(meters.get("memetrade.data.requests.created")
                        .tag("type", "CORRECTION")
                        .tag("origin", "EMAIL")
                        .counter()
                        .count())
                .isEqualTo(2.0);
        meters.getMeters().forEach(meter -> assertThat(meter.getId().getTags())
                .allMatch(tag -> tag.getKey().equals("type") || tag.getKey().equals("origin")));
    }

    @Test
    void theVerificationDomainIsTheHostAndPortOfTheGuestApp() {
        assertThat(PrivacyConfig.domain(URI.create("http://localhost:3000"))).isEqualTo("localhost:3000");
        assertThat(PrivacyConfig.domain(URI.create("https://app.example.org"))).isEqualTo("app.example.org");
    }

    @Test
    void theVerificationMessageIsUniquePerRequestAndRefusesABadDomain() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Instant until = NOW.plusSeconds(86_400);

        String one = VerificationMessage.build("localhost:3000", first, until);
        String two = VerificationMessage.build("localhost:3000", second, until);

        assertThat(one).isNotEqualTo(two).contains("Válida até: " + until).contains("Pedido: " + first);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> VerificationMessage.build("a\nb", first, until))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> VerificationMessage.build(" ", first, until))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
