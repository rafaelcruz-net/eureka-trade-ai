package com.memetrade.pnl.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.audit.api.RateLimitedAuditPublisher;
import com.memetrade.testsupport.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 261121-paper-real-unico U3, Etapa 11 (test after the layer; NFR1.10, NFR1.15): the audit of a refused read never
 * delays the answer. With a publisher that blocks for 1 s, handing the 404 of another tenant (C5 and both sides of C6)
 * and the 422 of C6 to the audit returns in less than 100 ms each, whether the task runs, waits in the queue or is
 * refused by a full queue. The route side of the same promise is checked end to end by {@code ReadRefusalAuditLimitIT}.
 */
class ReadRefusalAuditNonBlockingTest {

    private final ReadRefusalAudit audit = new ReadRefusalAudit(
            (command, fingerprint, deadline) -> {
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return RateLimitedAuditPublisher.Outcome.WRITTEN;
            },
            new MutableClock(Instant.parse("2026-11-23T12:00:00Z")),
            new PnlReadAuditMetrics(new SimpleMeterRegistry()));

    @AfterEach
    void stop() {
        audit.destroy();
    }

    @Test
    void theFourRefusalsOfTheRoutesAreHandedOverInLessThan100MsEach() {
        assertFast(() -> audit.tenantMismatch(ReadRefusalAuditTest.person(), "LINES", "NONE", ids()));
        assertFast(() -> audit.tenantMismatch(ReadRefusalAuditTest.person(), "COMPARE", "PAPER", ids()));
        assertFast(() -> audit.tenantMismatch(ReadRefusalAuditTest.person(), "COMPARE", "REAL", ids()));
        assertFast(() -> audit.templateMismatch(ReadRefusalAuditTest.person(), "COMPARE", ids()));
    }

    @Test
    void evenAFullQueueNeverMakesTheRequestWait() {
        for (int i = 0; i < 40; i++) {
            assertFast(() -> audit.tenantMismatch(ReadRefusalAuditTest.person(), "LINES", "NONE", ids()));
        }
    }

    private static List<UUID> ids() {
        return List.of(UUID.randomUUID());
    }

    private static void assertFast(Runnable handOver) {
        long started = System.nanoTime();
        handOver.run();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(100));
    }
}
