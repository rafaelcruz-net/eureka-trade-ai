package com.memetrade.ops;

import static com.memetrade.testsupport.U5Cycles.buySignal;
import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.bots.domain.BotRef;
import com.memetrade.bots.domain.TenantScope;
import com.memetrade.bots.worker.BotCyclesHealthIndicator;
import com.memetrade.bots.worker.CycleResult;
import com.memetrade.bots.worker.CycleRunner;
import com.memetrade.identity.api.Principal;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.testsupport.AbstractU5WorkerIT;
import com.memetrade.testsupport.OutboxEvents;
import com.memetrade.testsupport.SensitiveValueScanner;
import com.memetrade.testsupport.U5Bots;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Passo 16 of U5 (NFR7.1, NFR9.1 to NFR9.5): a whole cycle and the commands in the worker, then: the log carries the
 * cycleId as correlation and the identifiers only (no mint, wallet, token nor amount); the error metric is labelled by
 * cause only; one audit event per action and none for an idempotent repetition; the informational health component.
 */
@ExtendWith(OutputCaptureExtension.class)
class U5ObservabilityIT extends AbstractU5WorkerIT {

    private static final Instant SLOT = Instant.parse("2026-10-28T14:00:00Z");

    @Autowired
    private CycleRunner cycles;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private BotCyclesHealthIndicator health;

    /**
     * The events of every logger of the application are collected by an appender of the test (deterministic, whatever
     * the console capture), with the MDC of each line.
     */
    @Test
    void aCycleLogsItsIdentifiersAndNothingSensitive(CapturedOutput output) {
        Principal person = newPerson();
        TokenId token = newToken();
        UUID botId = fixtures.active(person, token, U5Bots.wideLimits());
        clock.set(SLOT.plusSeconds(60));
        buySignal(market, assessments, token, SLOT);
        ch.qos.logback.classic.Logger application =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("com.memetrade");
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> events =
                new ch.qos.logback.core.read.ListAppender<>();
        events.start();
        application.addAppender(events);
        CycleResult result;
        try {
            result = cycles.run(new BotRef(botId, TenantScope.of(person)), SLOT);
        } finally {
            application.detachAppender(events);
        }

        String cycleId = result.cycleId().orElseThrow().toString();
        var cycleLine = events.list.stream()
                .filter(event -> event.getFormattedMessage().startsWith("Bot cycle:"))
                .findFirst()
                .orElseThrow();
        assertThat(cycleLine.getFormattedMessage())
                .contains(cycleId)
                .contains(botId.toString())
                .contains("action=BUY");
        assertThat(cycleLine.getMDCPropertyMap()).containsEntry("correlationId", cycleId);
        String all = String.join(
                "\n", events.list.stream().map(e -> e.getFormattedMessage()).toList());
        assertThat(all).doesNotContain(token.mint());
        assertThat(new SensitiveValueScanner().scan("worker log", all + output.getAll()))
                .isEmpty();
    }

    @Test
    void theErrorMetricIsLabelledByCauseOnly() {
        assertThat(meters.find("bots.cycle.errors").counters()).isNotEmpty().allSatisfy(counter -> assertThat(
                        counter.getId().getTags())
                .extracting(tag -> tag.getKey())
                .containsExactly("cause"));
    }

    @Test
    void oneEventPerActionAndNoneForARepetition() {
        Principal person = newPerson();
        UUID botId = fixtures.active(person, newToken());
        var key = U5Bots.key();

        fixtures.as(person, () -> commands.pause(person, botId, key));
        fixtures.as(person, () -> commands.pause(person, botId, key));

        assertThat(OutboxEvents.count(jdbc, "BOT_CREATED", "bot:" + botId)).isEqualTo(1);
        assertThat(OutboxEvents.count(jdbc, "BOT_STARTED", "bot:" + botId)).isEqualTo(1);
        assertThat(OutboxEvents.count(jdbc, "BOT_PAUSED", "bot:" + botId)).isEqualTo(1);
        assertThat(OutboxEvents.payloads(jdbc, "BOT_PAUSED"))
                .filteredOn(payload -> payload.contains(botId.toString()))
                .allMatch(payload -> payload.contains("PERSON"));
    }

    @Test
    void theHealthOfTheCyclesIsDownOnlyAfterTenSilentMinutesWithActiveBots() {
        Principal person = newPerson();
        TokenId token = newToken();
        UUID botId = fixtures.active(person, token, U5Bots.wideLimits());
        // Later than every slot of the other tests, so the last finished cycle of the database is this one.
        Instant slot = Instant.parse("2027-06-01T10:00:00Z");
        clock.set(slot.plusSeconds(60));
        buySignal(market, assessments, token, slot);
        cycles.run(new BotRef(botId, TenantScope.of(person)), slot);

        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
        clock.set(slot.plus(Duration.ofMinutes(30)));
        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.health().getDetails()).containsEntry("reason", "NO_SLOT_COMPLETED");
    }
}
