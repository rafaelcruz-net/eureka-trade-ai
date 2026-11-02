package com.memetrade.shared.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.memetrade.audit.infra.AuditOutboxRelay;
import com.memetrade.audit.infra.AuditProperties;
import com.memetrade.audit.infra.AuditRelayHealthIndicator;
import com.memetrade.marketdata.api.SourceStatus;
import com.memetrade.marketdata.domain.InMemoryMarketStore;
import com.memetrade.marketdata.infra.MarketSourcesHealth;
import com.memetrade.marketdata.infra.simulated.SimulatedMarketSource;
import com.memetrade.testsupport.MutableClock;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
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

    /**
     * U4 replaces {@code marketSource} by {@code marketSources} (reliability-design.md, Q1): UNKNOWN while every
     * source is PENDING, UP with lastUpdatedAt once one collected, DEGRADED only when every source FAILED, never DOWN.
     */
    @Test
    void theSourcesAreUnknownUntilOneCollectsAndThenUpWithTheLastUpdate() {
        InMemoryMarketStore store = new InMemoryMarketStore();
        store.registerSource(SimulatedMarketSource.SOURCE_A, Duration.ofSeconds(30));
        MarketSourcesHealth indicator = new MarketSourcesHealth(store);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UNKNOWN);

        store.updateSourceStatus(
                "sim-a", SourceStatus.State.OK, Optional.empty(), clock.instant(), Optional.of(clock.instant()));
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(indicator.health().getDetails()).containsKey("lastUpdatedAt");

        clock.advance(Duration.ofSeconds(60));
        assertThat(indicator.health().getStatus()).as("age no longer degrades").isEqualTo(Status.UP);
    }

    @Test
    void migrationsAreDownWhenTheDatabaseCannotBeReached() throws SQLException {
        DataSource broken = mock(DataSource.class);
        when(broken.getConnection()).thenThrow(new SQLException("connection refused"));

        assertThat(new MigrationsHealthIndicator(broken).health().getStatus()).isEqualTo(Status.DOWN);
    }
}
