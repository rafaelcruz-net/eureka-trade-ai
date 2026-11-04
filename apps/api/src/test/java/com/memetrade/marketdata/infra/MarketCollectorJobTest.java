package com.memetrade.marketdata.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import com.memetrade.marketdata.api.FailureCategory;
import com.memetrade.marketdata.api.MarketCollectionCompleted;
import com.memetrade.marketdata.api.SourceStatus;
import com.memetrade.marketdata.domain.CollectionOutcome;
import com.memetrade.marketdata.domain.InMemoryMarketStore;
import com.memetrade.marketdata.domain.MarketCollector;
import com.memetrade.marketdata.domain.UnitOfWork;
import com.memetrade.marketdata.infra.simulated.SimulatedMarketSource;
import com.memetrade.testsupport.InMemoryLockProvider;
import com.memetrade.testsupport.MutableClock;
import com.memetrade.testsupport.NoWaitSleeper;
import com.memetrade.testsupport.ScriptedMarketSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Executors;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** The collection schedule per source, its lock, metrics and the completion event. */
@Tag("u1")
class MarketCollectorJobTest {

    private final MutableClock clock = new MutableClock();
    private final InMemoryMarketStore store = spy(new InMemoryMarketStore());
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final InMemoryLockProvider locks = new InMemoryLockProvider();
    private final List<Object> events = new ArrayList<>();
    private final ScriptedMarketSource sourceB = ScriptedMarketSource.of("sim-b");
    private final List<Runnable> started = new ArrayList<>();
    private final SourceCaller caller = new SourceCaller(
            Executors.newCachedThreadPool(), new NoWaitSleeper(), new Random(42), Duration.ofSeconds(2), (s, n) -> {});
    private final MarketCollectorJob job = new MarketCollectorJob(
            new MarketCollectorJob.Settings(
                    List.of(new SimulatedMarketSource(42, 60, clock), sourceB),
                    new MarketCollector(store, caller, UnitOfWork.DIRECT, clock),
                    store,
                    new MarketSourceRegistry(
                            List.of(new SimulatedMarketSource(42, 60, clock), sourceB),
                            store,
                            new MarketDataProperties(
                                    List.of("sim-a", "sim-b"),
                                    Duration.ofSeconds(30),
                                    new BigDecimal("5.0000"),
                                    new MarketDataProperties.Simulated(42, 60))),
                    new DefaultLockingTaskExecutor(locks),
                    started::add,
                    events::add,
                    Duration.ofSeconds(30),
                    clock),
            registry);

    @Test
    void beforeTheFirstCollectionTheSourceIsPendingWithNoAge() {
        assertThat(gauge("market_source_status", "sim-a")).isZero();
        assertThat(gauge("market_source_last_collection_age_seconds", "sim-a")).isEqualTo(-1.0);
        assertThat(store.sources).containsKeys("sim-a", "sim-b");
    }

    @Test
    void afterACollectionTheGaugesShowOkAndTheAge() {
        job.collectNow("sim-a");
        clock.advance(Duration.ofSeconds(20));

        assertThat(gauge("market_source_status", "sim-a")).isEqualTo(1.0);
        assertThat(gauge("market_source_last_collection_age_seconds", "sim-a")).isEqualTo(20.0);
        assertThat(store.tokens).hasSize(60);
        assertThat(registry.get("market_collection_total")
                        .tag("result", "OK")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @Tag("u4")
    void aCollectionPublishesTheCompletionEventAfterItsWrites() {
        job.collectNow("sim-b");

        assertThat(events)
                .containsExactly(new MarketCollectionCompleted("sim-b", SourceStatus.State.OK, clock.instant()));
    }

    @Test
    @Tag("u4")
    void anOverlappingCollectionIsSkippedByTheLockAndCounted() {
        locks.hold("market-collect-sim-a");

        Optional<CollectionOutcome> skipped = job.collectNow("sim-a");

        assertThat(skipped).isEmpty();
        assertThat(registry.get("market_collection_total")
                        .tag("result", "SKIPPED_LOCKED")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(events).isEmpty();
    }

    @Test
    @Tag("u4")
    void eachSourceIsDispatchedOnceAtStartAndAgainOnlyAfterItsInterval() {
        job.dispatch();
        job.dispatch();
        assertThat(started).hasSize(2);

        started.forEach(Runnable::run);
        clock.advance(Duration.ofSeconds(29));
        job.dispatch();
        assertThat(started).hasSize(2);

        clock.advance(Duration.ofSeconds(1));
        job.dispatch();
        assertThat(started).hasSize(4);
    }

    @Test
    @Tag("u4")
    void aFailingSourceIsCountedAsFailedWithoutTouchingTheOther() {
        sourceB.thenFail(FailureCategory.ERROR_RESPONSE)
                .thenFail(FailureCategory.ERROR_RESPONSE)
                .thenFail(FailureCategory.ERROR_RESPONSE);

        job.collectNow("sim-b");
        job.collectNow("sim-a");

        assertThat(gauge("market_source_status", "sim-b")).isEqualTo(3.0);
        assertThat(gauge("market_source_status", "sim-a")).isEqualTo(1.0);
        assertThat(registry.get("market_collection_total")
                        .tag("source", "sim-b")
                        .tag("result", "FAILED")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    /** NFR9.6: the failure log has the source, the category and the exception class, never the error text. */
    @Test
    @Tag("u4")
    void aFailedCollectionLogsTheCategoryAndTheClassButNeverTheErrorText() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(MarketCollectorJob.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        ch.qos.logback.classic.Level previous = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
        try {
            for (int i = 0; i < 3; i++) {
                sourceB.thenThrow(new IllegalStateException("upstream said SECRET-DETAIL-42 for MintXYZ"));
            }
            job.collectNow("sim-b");
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }

        List<String> errors = appender.list.stream()
                .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .toList();
        assertThat(errors)
                .singleElement()
                .asString()
                .contains("source=sim-b")
                .contains("failureCategory=ERROR_RESPONSE")
                .contains("exceptionClass=IllegalStateException")
                .doesNotContain("SECRET")
                .doesNotContain("MintXYZ");
        assertThat(appender.list)
                .anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("state changed"));
    }

    @Test
    void theCycleNeverThrowsWhenTheDatabaseFails() {
        doThrow(new DataAccessResourceFailureException("down"))
                .when(store)
                .writeCollection(org.mockito.ArgumentMatchers.any());

        assertThatCode(() -> assertThat(job.collectNow("sim-a")).isEmpty()).doesNotThrowAnyException();
    }

    private double gauge(String name, String source) {
        return registry.get(name).tag("source", source).gauge().value();
    }
}
