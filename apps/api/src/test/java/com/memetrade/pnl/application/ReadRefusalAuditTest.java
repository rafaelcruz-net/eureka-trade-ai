package com.memetrade.pnl.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.RateLimitedAuditPublisher;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.testsupport.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 261121-paper-real-unico U3, Etapa 11 (test after the layer; NFR1.10, NFR1.15, NFR3.14, NFR3.16): the bounded executor
 * of the audit of the refused reads. One thread and a queue of 16: with the publisher held and different people, one
 * task runs, 16 wait, the 18th is refused at once, counted and logged at {@code ERROR}; repeated failures of the same
 * person and action are counted every time and logged once a minute, never with the message of the failure.
 */
class ReadRefusalAuditTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-23T12:00:00Z"));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final PnlReadAuditMetrics metrics = new PnlReadAuditMetrics(meters);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(ReadRefusalAudit.class);
    private ReadRefusalAudit audit;

    @BeforeEach
    void capture() {
        logs.start();
        logger.addAppender(logs);
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void release() {
        logger.detachAppender(logs);
        if (audit != null) {
            audit.destroy();
        }
    }

    @Test
    void theLimitsAreTheOnesOfTheDesign() {
        assertThat(ReadRefusalAudit.THREADS).isEqualTo(1);
        assertThat(ReadRefusalAudit.QUEUE).isEqualTo(16);
        assertThat(ReadRefusalAudit.BUDGET).isEqualTo(Duration.ofMillis(200));
        assertThat(ReadRefusalAudit.SUBMITTED_WINDOW).isEqualTo(Duration.ofMinutes(60));
        assertThat(ReadRefusalAudit.SUBMITTED_KEYS).isEqualTo(10_000);
        assertThat(ReadRefusalAudit.LOG_EVERY).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void oneRunsSixteenWaitAndTheEighteenthIsRefusedCountedAndLoggedOnce() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger written = new AtomicInteger();
        audit = new ReadRefusalAudit(
                (command, fingerprint, deadline) -> {
                    started.countDown();
                    await(held);
                    written.incrementAndGet();
                    return RateLimitedAuditPublisher.Outcome.WRITTEN;
                },
                clock,
                metrics);

        audit.tenantMismatch(person(), "LINES", "NONE", List.of(UUID.randomUUID()));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 16; i++) {
            audit.tenantMismatch(person(), "LINES", "NONE", List.of(UUID.randomUUID()));
        }
        assertThat(failures("TENANT_MISMATCH")).isZero();
        audit.tenantMismatch(person(), "COMPARE", "REAL", List.of(UUID.randomUUID()));

        assertThat(failures("TENANT_MISMATCH")).isEqualTo(1);
        assertThat(errors()).hasSize(1);
        assertThat(errors().getFirst().getFormattedMessage())
                .contains("event=PNL_READ_AUDIT_FAILED")
                .contains("action=TENANT_MISMATCH")
                .contains("category=QUEUE_FULL")
                .contains("exceptionClass=RejectedExecutionException");
        held.countDown();
    }

    @Test
    void tenFailuresOfTheSamePersonAndActionInAMinuteAreTenCountsAndOneLog() throws Exception {
        audit = new ReadRefusalAudit(
                (command, fingerprint, deadline) -> {
                    throw new IllegalStateException("secret detail of the database");
                },
                clock,
                metrics);
        Principal person = person();

        for (int i = 0; i < 10; i++) {
            double before = failures("TEMPLATE_MISMATCH");
            audit.templateMismatch(person, "COMPARE", List.of(UUID.randomUUID(), UUID.randomUUID()));
            waitFor(() -> failures("TEMPLATE_MISMATCH") > before);
            clock.advance(Duration.ofSeconds(5));
        }

        assertThat(failures("TEMPLATE_MISMATCH")).isEqualTo(10);
        assertThat(errors()).hasSize(1);
        assertThat(errors().getFirst().getFormattedMessage())
                .contains("category=ERROR")
                .contains("exceptionClass=IllegalStateException")
                .doesNotContain("secret");
    }

    @Test
    void aTaskPastItsBudgetGivesUpWithoutWritingAsADeadline() throws Exception {
        AtomicInteger published = new AtomicInteger();
        audit = new ReadRefusalAudit(
                (command, fingerprint, deadline) -> {
                    published.incrementAndGet();
                    return RateLimitedAuditPublisher.Outcome.WRITTEN;
                },
                new java.time.Clock() {
                    private final AtomicInteger reads = new AtomicInteger();

                    @Override
                    public java.time.ZoneId getZone() {
                        return java.time.ZoneOffset.UTC;
                    }

                    @Override
                    public java.time.Clock withZone(java.time.ZoneId zone) {
                        return this;
                    }

                    @Override
                    public Instant instant() {
                        // The first read is the submission; every later read is past the budget.
                        return reads.getAndIncrement() == 0
                                ? Instant.parse("2026-11-23T12:00:00Z")
                                : Instant.parse("2026-11-23T12:00:01Z");
                    }
                },
                metrics);

        audit.tenantMismatch(person(), "LINES", "NONE", List.of(UUID.randomUUID()));
        waitFor(() -> failures("TENANT_MISMATCH") == 1);

        assertThat(published).hasValue(0);
        assertThat(errors().getFirst().getFormattedMessage()).contains("category=DEADLINE");
    }

    @Test
    void theEventCarriesOnlyTheRouteTheSideTheBotsAndTheFingerprint() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AuditEventCommand[] seen = new AuditEventCommand[1];
        audit = new ReadRefusalAudit(
                (command, fingerprint, deadline) -> {
                    seen[0] = command;
                    done.countDown();
                    return RateLimitedAuditPublisher.Outcome.WRITTEN;
                },
                clock,
                metrics);
        Principal person = person();
        UUID bot = UUID.randomUUID();

        audit.tenantMismatch(person, "COMPARE", "REAL", List.of(bot));

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seen[0].action()).isEqualTo("TENANT_MISMATCH");
        assertThat(seen[0].actor()).isEqualTo(person.userId().toString());
        assertThat(seen[0].details())
                .containsOnlyKeys("route", "side", "botIds", "fingerprint")
                .containsEntry("route", "COMPARE")
                .containsEntry("side", "REAL")
                .containsEntry("botIds", bot.toString())
                .containsEntry(
                        "fingerprint", ReadRefusalAudit.fingerprint(person.userId(), ReadAuditAction.TENANT_MISMATCH));
    }

    static Principal person() {
        return new Principal(UUID.randomUUID(), UUID.randomUUID(), Role.BETA_USER);
    }

    private double failures(String action) {
        return meters.find(PnlReadAuditMetrics.FAILURES)
                .tag("action", action)
                .counter()
                .count();
    }

    private List<ILoggingEvent> errors() {
        return logs.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .toList();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", interrupted);
        }
    }

    static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < end) {
            Thread.sleep(5);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
