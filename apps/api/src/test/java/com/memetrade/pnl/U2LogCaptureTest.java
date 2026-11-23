package com.memetrade.pnl;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.memetrade.audit.api.RateLimitedAuditPublisher;
import com.memetrade.pnl.api.PnlWindow;
import com.memetrade.pnl.application.PnlRealMetrics;
import com.memetrade.pnl.application.ReconciliationAuditGate;
import com.memetrade.pnl.infra.PnlMismatchAuditProperties;
import com.memetrade.pnl.real.ReconciliationResult;
import com.memetrade.shared.web.CorrelationId;
import com.memetrade.testsupport.MutableClock;
import com.memetrade.testsupport.SyntheticIds;
import com.memetrade.testsupport.TestWallet;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * 261121-paper-real-unico U2, Etapa 22 (written after the layer; NFR3.7, NFR3.12, NFR3.13; observability-design.md
 * "Logs"): the logs of a REAL P&L mismatch and of a failed audit write are ONE record each, in English, with the
 * correlation id of the MDC, the checks and the category; never an address, a signature, a mint nor the differences in
 * lamports (they stay in the audit event).
 *
 * <p>Decision of the founder of 2026-11-23 (review R-05, NFR3.15): the error log of a mismatch is written at most once
 * per minute per person and kind of check, while the counter {@code pnl.real.reconciliation.mismatches} still counts
 * every read (the alarm does not change). The audit command keeps the correlation id of the read even when it is written
 * after the read, with another MDC.
 */
class U2LogCaptureTest {

    private static final String CORRELATION = "8c7a2b1e-0000-4000-8000-00000000c0de";

    private final Logger logger = (Logger) LoggerFactory.getLogger(ReconciliationAuditGate.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-23T12:00:00Z"));
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final String wallet = TestWallet.generate().address();
    private final String signature = SyntheticIds.signature();
    private final String mint = SyntheticIds.mint();

    @BeforeEach
    void capture() {
        appender.start();
        logger.addAppender(appender);
        MDC.put(CorrelationId.MDC_KEY, CORRELATION);
    }

    @AfterEach
    void release() {
        logger.detachAppender(appender);
        MDC.clear();
    }

    private static ReconciliationResult mismatch(ReconciliationResult.Check... checks) {
        return new ReconciliationResult(
                PnlWindow.all(),
                ReconciliationResult.Status.MISMATCH,
                -123_456_789L,
                0,
                Optional.empty(),
                List.of(checks),
                Map.of("REALIZED", -123_456_789L));
    }

    private ReconciliationAuditGate gate(RateLimitedAuditPublisher publisher) {
        return new ReconciliationAuditGate(
                publisher, clock, new PnlRealMetrics(registry), PnlMismatchAuditProperties.defaults());
    }

    private List<ILoggingEvent> logged(String event) {
        return appender.list.stream()
                .filter(record -> record.getFormattedMessage().contains("event=" + event))
                .toList();
    }

    @Test
    void aMismatchIsOneErrorRecordWithTheChecksAndTheCorrelationIdOnly() {
        AtomicReference<String> written = new AtomicReference<>();
        ReconciliationAuditGate gate = gate((command, fingerprint, deadline) -> {
            written.set(command.correlationId());
            return RateLimitedAuditPublisher.Outcome.WRITTEN;
        });
        try {
            var answer = gate.onMismatch(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    mismatch(ReconciliationResult.Check.REALIZED, ReconciliationResult.Check.UNKNOWN_BOT));
            MDC.clear(); // the audit runs later, on the thread of the block after its transaction, with another MDC
            assertThat(answer.audit()).isEqualTo("true");
        } finally {
            gate.destroy();
        }

        assertThat(written.get()).isEqualTo(CORRELATION);
        assertThat(logged("REAL_PNL_RECONCILIATION_MISMATCH")).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains("UNKNOWN_BOT").contains("REALIZED");
            assertThat(event.getMDCPropertyMap()).containsEntry(CorrelationId.MDC_KEY, CORRELATION);
        });
        assertNothingForbidden();
    }

    @Test
    void aFailedWriteIsOneErrorRecordWithItsCategory() {
        ReconciliationAuditGate gate = gate((command, fingerprint, deadline) -> {
            throw new RateLimitedAuditPublisher.DeadlinePassed();
        });
        try {
            assertThat(gate.onMismatch(
                                    UUID.randomUUID(), UUID.randomUUID(), mismatch(ReconciliationResult.Check.REALIZED))
                            .audit())
                    .isEqualTo("failed");
        } finally {
            gate.destroy();
        }

        assertThat(logged("REAL_PNL_MISMATCH_AUDIT_FAILED")).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains("category=DEADLINE");
        });
        assertThat(logged("REAL_PNL_RECONCILIATION_MISMATCH")).hasSize(1);
        assertNothingForbidden();
    }

    @Test
    void theErrorLogIsAtMostOncePerMinutePerPersonAndCheckWhileTheCounterCountsEveryRead() {
        ReconciliationAuditGate gate =
                gate((command, fingerprint, deadline) -> RateLimitedAuditPublisher.Outcome.WRITTEN);
        UUID person = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        try {
            for (int i = 0; i < 10; i++) {
                gate.onMismatch(UUID.randomUUID(), person, mismatch(ReconciliationResult.Check.REALIZED));
            }
            assertThat(logged("REAL_PNL_RECONCILIATION_MISMATCH"))
                    .as("ten reads in a minute")
                    .hasSize(1);

            gate.onMismatch(UUID.randomUUID(), other, mismatch(ReconciliationResult.Check.REALIZED));
            assertThat(logged("REAL_PNL_RECONCILIATION_MISMATCH"))
                    .as("another person")
                    .hasSize(2);

            gate.onMismatch(UUID.randomUUID(), person, mismatch(ReconciliationResult.Check.FEES));
            assertThat(logged("REAL_PNL_RECONCILIATION_MISMATCH"))
                    .as("another kind of check")
                    .hasSize(3);

            clock.advance(Duration.ofSeconds(59));
            gate.onMismatch(UUID.randomUUID(), person, mismatch(ReconciliationResult.Check.REALIZED));
            assertThat(logged("REAL_PNL_RECONCILIATION_MISMATCH"))
                    .as("still in the minute")
                    .hasSize(3);

            clock.advance(Duration.ofSeconds(2));
            gate.onMismatch(UUID.randomUUID(), person, mismatch(ReconciliationResult.Check.REALIZED));
            assertThat(logged("REAL_PNL_RECONCILIATION_MISMATCH"))
                    .as("a minute later")
                    .hasSize(4);
        } finally {
            gate.destroy();
        }

        assertThat(registry.counter(PnlRealMetrics.MISMATCHES, "check", "REALIZED")
                        .count())
                .as("every read counts")
                .isEqualTo(13);
        assertThat(registry.counter(PnlRealMetrics.MISMATCHES, "check", "FEES").count())
                .isEqualTo(1);
    }

    private void assertNothingForbidden() {
        for (ILoggingEvent event : appender.list) {
            assertThat(event.getFormattedMessage())
                    .doesNotContain(wallet)
                    .doesNotContain(signature)
                    .doesNotContain(mint)
                    .doesNotContain("123456789");
        }
    }
}
