package com.memetrade.shared.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.memetrade.audit.infra.AuditOutboxRelay;
import com.memetrade.audit.infra.AuditProperties;
import com.memetrade.audit.infra.AuditRelayHealthIndicator;
import com.memetrade.marketdata.domain.InMemoryMarketStore;
import com.memetrade.marketdata.domain.MarketCollector;
import com.memetrade.marketdata.infra.MarketCollectorJob;
import com.memetrade.marketdata.infra.MarketSourceHealthIndicator;
import com.memetrade.marketdata.infra.simulated.SimulatedMarketSource;
import com.memetrade.testsupport.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.dao.DataAccessResourceFailureException;

@Tag("u1")
class HealthIndicatorsTest {

    private final MutableClock clock = new MutableClock();
    private final AuditOutboxRelay relay = mock(AuditOutboxRelay.class);
    private final AuditRelayHealthIndicator audit = new AuditRelayHealthIndicator(
            relay, AuditProperties.relayOnly(Duration.ofSeconds(1), 100, Duration.ofSeconds(60)));

    @Test
    void theRelayIsUpBelow60SecondsAndDegradedAbove() {
        when(relay.lag()).thenReturn(new AuditOutboxRelay.OutboxLag(2, Duration.ofSeconds(59)));
        assertThat(audit.health().getStatus()).isEqualTo(Status.UP);

        when(relay.lag()).thenReturn(new AuditOutboxRelay.OutboxLag(2, Duration.ofSeconds(61)));
        assertThat(audit.health().getStatus()).isEqualTo(HealthStatuses.DEGRADED);
        assertThat(audit.health().getDetails()).containsEntry("pending", 2L);
    }

    @Test
    void aDatabaseErrorLeavesTheRelayDegradedNotCrashing() {
        when(relay.lag()).thenThrow(new DataAccessResourceFailureException("down"));

        assertThat(audit.health().getStatus()).isEqualTo(HealthStatuses.DEGRADED);
    }

    @Test
    void theSourceIsDegradedUntilItCollectsAndAgainWhenTheLastCollectionIsOlderThan60Seconds() {
        InMemoryMarketStore store = new InMemoryMarketStore();
        MarketCollectorJob job = new MarketCollectorJob(
                new MarketCollector(new SimulatedMarketSource(42, 10, clock), store, clock),
                store,
                clock,
                new SimpleMeterRegistry());
        MarketSourceHealthIndicator indicator = new MarketSourceHealthIndicator(job);

        assertThat(indicator.health().getStatus()).isEqualTo(HealthStatuses.DEGRADED);
        assertThat(indicator.health().getDetails()).containsEntry("status", "NEVER_COLLECTED");

        job.run();
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(indicator.health().getDetails()).containsKey("lastUpdatedAt").containsEntry("simulated", true);

        clock.advance(Duration.ofSeconds(60));
        assertThat(indicator.health().getStatus()).isEqualTo(HealthStatuses.DEGRADED);
    }

    @Test
    void migrationsAreDownWhenTheDatabaseCannotBeReached() throws SQLException {
        DataSource broken = mock(DataSource.class);
        when(broken.getConnection()).thenThrow(new SQLException("connection refused"));

        assertThat(new MigrationsHealthIndicator(broken).health().getStatus()).isEqualTo(Status.DOWN);
    }
}
