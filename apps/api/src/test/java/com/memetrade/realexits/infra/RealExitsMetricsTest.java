package com.memetrade.realexits.infra;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.realexits.application.ExitEvents;
import com.memetrade.realexits.domain.AdapterKind;
import com.memetrade.realexits.domain.CancelReason;
import com.memetrade.realexits.domain.ConditionalKind;
import com.memetrade.realexits.domain.ConditionalOrder;
import com.memetrade.realexits.domain.ConditionalState;
import com.memetrade.realexits.domain.ExitSide;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

/**
 * Step 13 of the U7, written after the code (U7-NFR5.1 to U7-NFR5.6b, U7-NFR5.11): the metrics of the conditional exits
 * have closed labels and every counter starts at zero; the gauges exist once the job ran; the logs never carry an
 * address, the transaction nor a whole signature.
 */
@Tag("u7-exits-and-fiscal")
class RealExitsMetricsTest {

    private static final String SIGNATURE = "5".repeat(88);
    private static final String MINT = "Mint1111111111111111111111111111111111111111";

    @Test
    void everyCounterIsRegisteredWithZeroForEveryValueOfItsLabel() {
        MeterRegistry meters = new SimpleMeterRegistry();
        new RealExitsMetrics(meters);

        for (ExitSide side : ExitSide.values()) {
            assertThat(meters.counter("real_conditional_fills", "side", side.name())
                            .count())
                    .isZero();
        }
        for (CancelReason reason : CancelReason.values()) {
            assertThat(meters.counter("real_conditional_cancels", "reason", reason.name())
                            .count())
                    .isZero();
        }
        for (String result : RealExitsMetrics.EXPORT_RESULTS) {
            assertThat(meters.counter("real_fiscal_exports", "result", result).count())
                    .isZero();
        }
        for (String job : RealExitsMetrics.JOBS) {
            assertThat(meters.counter("real_exits_job_runs", "job", job).count())
                    .isZero();
        }
        assertThat(meters.find("real_conditionals").gauges())
                .as("gauges only after the job")
                .isEmpty();
    }

    @Test
    void theGaugesFollowTheLastRunOfTheJob() {
        MeterRegistry meters = new SimpleMeterRegistry();
        RealExitsMetrics metrics = new RealExitsMetrics(meters);
        Map<ConditionalState, Long> counts = new EnumMap<>(ConditionalState.class);
        for (ConditionalState state : ConditionalState.values()) {
            counts.put(state, 0L);
        }
        counts.put(ConditionalState.CANCELLING, 2L);

        metrics.gauges(counts, 901, 30);
        metrics.gauges(counts, 960, 60);

        assertThat(meters.get("real_conditionals")
                        .tag("state", "CANCELLING")
                        .gauge()
                        .value())
                .isEqualTo(2.0);
        assertThat(meters.get("real_conditional_cancelling_oldest_seconds")
                        .gauge()
                        .value())
                .isEqualTo(960.0);
        assertThat(meters.get("real_conditional_unread_oldest_seconds").gauge().value())
                .isEqualTo(60.0);
    }

    @Test
    void aFillCountsItsSideAndItsLag() {
        MeterRegistry meters = new SimpleMeterRegistry();
        RealExitsMetrics metrics = new RealExitsMetrics(meters);

        metrics.filled(ExitSide.TAKE_PROFIT, 42);
        metrics.cancelled(CancelReason.KILL_SWITCH);
        metrics.exported("RATE_LIMITED");

        assertThat(meters.counter("real_conditional_fills", "side", "TAKE_PROFIT")
                        .count())
                .isEqualTo(1.0);
        assertThat(meters.get("real_conditional_fill_lag").summary().totalAmount())
                .isEqualTo(42.0);
        assertThat(meters.counter("real_conditional_cancels", "reason", "KILL_SWITCH")
                        .count())
                .isEqualTo(1.0);
        assertThat(meters.counter("real_fiscal_exports", "result", "RATE_LIMITED")
                        .count())
                .isEqualTo(1.0);
    }

    /** U7-NFR5.11: the log of a change of state masks the signature and carries no address. */
    @Test
    void theLogOfAChangeOfStateMasksTheSignatureAndCarriesNoAddress() {
        Logger logger = (Logger) LoggerFactory.getLogger(ExitEvents.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ExitEvents events = new ExitEvents(
                    Mockito.mock(AuditEventPublisher.class),
                    new RealExitsMetrics(new SimpleMeterRegistry()),
                    Clock.fixed(Instant.parse("2026-11-17T12:00:00Z"), ZoneOffset.UTC));

            events.changed(conditional(), ConditionalState.FILLED, "STOP", Optional.empty(), Optional.of(SIGNATURE));

            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage())
                        .doesNotContain(SIGNATURE)
                        .doesNotContain(MINT);
                assertThat(event.getFormattedMessage()).contains("to=FILLED");
            });
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static ConditionalOrder conditional() {
        Instant at = Instant.parse("2026-11-17T11:00:00Z");
        return new ConditionalOrder(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                MINT,
                ConditionalKind.STOP,
                Optional.of(new BigDecimal("0.1")),
                Optional.empty(),
                10L,
                200,
                ConditionalState.OPEN,
                AdapterKind.JUPITER,
                Optional.of("jup-1"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                "key",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                at,
                at);
    }
}
