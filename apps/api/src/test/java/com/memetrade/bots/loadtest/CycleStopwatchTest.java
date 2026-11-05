package com.memetrade.bots.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.memetrade.bots.worker.CycleResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * U5 Passo 20 (NFR2.1, NFR2.3): the stopwatch of the loadtest profile feeds the three timers and writes one timing line
 * per cycle with the stages of that cycle only.
 */
@Tag("u5")
class CycleStopwatchTest {

    @Test
    void eachCycleFeedsTheTimersAndWritesOneLineWithItsStages() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CycleStopwatch stopwatch = new CycleStopwatch(registry);
        Logger logger = (Logger) LoggerFactory.getLogger(CycleStopwatch.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        // Another test of the same JVM may have changed the level (MigrationRunner resets it); this test needs INFO.
        ch.qos.logback.classic.Level level = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
        try {
            CycleResult completed = new CycleResult(
                    CycleResult.Kind.COMPLETED,
                    Optional.of(UUID.randomUUID()),
                    Optional.of("BUY"),
                    Optional.of("FILLED"),
                    Optional.empty(),
                    Optional.empty());
            stopwatch.decision(Duration.ofMillis(120));
            stopwatch.barrierAndExecution(Duration.ofMillis(80));
            stopwatch.cycle(Duration.ofMillis(300), completed);
            stopwatch.decision(Duration.ofMillis(40));
            stopwatch.cycle(Duration.ofMillis(50), completed);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(level);
        }

        assertThat(registry.get("loadtest.bots.cycle").timer().count()).isEqualTo(2);
        assertThat(registry.get("loadtest.bots.decision").timer().count()).isEqualTo(2);
        assertThat(registry.get("loadtest.bots.gate").timer().count()).isEqualTo(1);
        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly(
                        "Loadtest cycle timing totalMs=300 decisionMs=120 gateMs=80 kind=COMPLETED",
                        "Loadtest cycle timing totalMs=50 decisionMs=40 gateMs=0 kind=COMPLETED");
    }
}
