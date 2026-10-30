package com.memetrade.audit.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

@Tag("u1")
class AuditRelayJobTest {

    private final AuditOutboxRelay relay = mock(AuditOutboxRelay.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AuditRelayJob job = new AuditRelayJob(relay, registry);

    @Test
    void publishesPendingAndOldestAgeGaugesAfterEachCycle() {
        when(relay.relayBatch()).thenReturn(3);
        when(relay.lag()).thenReturn(new AuditOutboxRelay.OutboxLag(2, Duration.ofSeconds(7)));

        job.cycle();

        assertThat(registry.get("audit_outbox_pending").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("audit_outbox_oldest_age_seconds").gauge().value())
                .isEqualTo(7.0);
        assertThat(job.lastLag().oldest()).contains(Duration.ofSeconds(7));
    }

    @Test
    void aDatabaseErrorEndsTheCycleWithoutPropagatingSoTheNextCycleRetries() {
        when(relay.relayBatch()).thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatCode(job::cycle).doesNotThrowAnyException();
        assertThatCode(job::run).doesNotThrowAnyException();
        assertThat(job.lastLag().pending()).isZero();
    }

    @Test
    void noPendingRowsMeansNoOldestAge() {
        assertThat(new AuditOutboxRelay.OutboxLag(0, Duration.ZERO).oldest()).isEmpty();
    }
}
