package com.memetrade.pnl.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.audit.api.RateLimitedAuditPublisher;
import com.memetrade.identity.api.Principal;
import com.memetrade.testsupport.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U3, Etapa 11 (test after the layer; NFR3.14, scalability-design.md): the filter in memory of
 * the audit of the refused reads. The same person and action twice in 60 minutes is one task; another action or person
 * is one more; past 60 minutes the key goes back; with 10,000 keys in the window the next one enters, the oldest leaves
 * and the new refusal goes on.
 */
class ReadRefusalAuditFilterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-23T12:00:00Z"));
    private final AtomicInteger tasks = new AtomicInteger();
    private final ReadRefusalAudit audit = new ReadRefusalAudit(
            (command, fingerprint, deadline) -> {
                tasks.incrementAndGet();
                return RateLimitedAuditPublisher.Outcome.WRITTEN;
            },
            clock,
            new PnlReadAuditMetrics(new SimpleMeterRegistry()));

    @AfterEach
    void stop() {
        audit.destroy();
    }

    @Test
    void theSamePersonAndActionTwiceInAnHourIsOneTask() throws Exception {
        Principal person = ReadRefusalAuditTest.person();

        audit.tenantMismatch(person, "LINES", "NONE", List.of(UUID.randomUUID()));
        ReadRefusalAuditTest.waitFor(() -> tasks.get() == 1);
        clock.advance(Duration.ofMinutes(59));
        audit.tenantMismatch(person, "COMPARE", "PAPER", List.of(UUID.randomUUID()));

        ReadRefusalAuditTest.waitFor(() -> tasks.get() == 1);
        Thread.sleep(50);
        assertThat(tasks).hasValue(1);
    }

    @Test
    void anotherActionOrAnotherPersonIsOneMore() throws Exception {
        Principal person = ReadRefusalAuditTest.person();

        audit.tenantMismatch(person, "LINES", "NONE", List.of(UUID.randomUUID()));
        audit.templateMismatch(person, "COMPARE", List.of(UUID.randomUUID()));
        audit.tenantMismatch(ReadRefusalAuditTest.person(), "LINES", "NONE", List.of(UUID.randomUUID()));

        ReadRefusalAuditTest.waitFor(() -> tasks.get() == 3);
    }

    @Test
    void afterAnHourTheKeyGoesBack() throws Exception {
        Principal person = ReadRefusalAuditTest.person();

        audit.tenantMismatch(person, "LINES", "NONE", List.of(UUID.randomUUID()));
        ReadRefusalAuditTest.waitFor(() -> tasks.get() == 1);
        clock.advance(Duration.ofMinutes(60));
        audit.tenantMismatch(person, "LINES", "NONE", List.of(UUID.randomUUID()));

        ReadRefusalAuditTest.waitFor(() -> tasks.get() == 2);
    }

    @Test
    void withTenThousandKeysTheNextEntersAndTheOldestLeaves() throws Exception {
        Instant now = clock.instant();
        assertThat(audit.mark("first", now)).isTrue();
        for (int i = 1; i < ReadRefusalAudit.SUBMITTED_KEYS; i++) {
            assertThat(audit.mark("key-" + i, now)).isTrue();
        }
        assertThat(audit.submittedKeys()).isEqualTo(ReadRefusalAudit.SUBMITTED_KEYS);
        assertThat(audit.mark("first", now)).isFalse();

        assertThat(audit.mark("the-10001st", now)).isTrue();

        assertThat(audit.submittedKeys()).isEqualTo(ReadRefusalAudit.SUBMITTED_KEYS);
        // The oldest key left: the first one is accepted again, and a new refusal still goes to the executor.
        assertThat(audit.mark("first", now)).isTrue();
        audit.tenantMismatch(ReadRefusalAuditTest.person(), "LINES", "NONE", List.of(UUID.randomUUID()));
        ReadRefusalAuditTest.waitFor(() -> tasks.get() == 1);
    }
}
