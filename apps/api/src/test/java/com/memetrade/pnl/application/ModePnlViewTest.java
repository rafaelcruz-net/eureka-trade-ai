package com.memetrade.pnl.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.pnl.api.PnlMarket;
import com.memetrade.pnl.infra.PnlByModeProperties;
import com.memetrade.pnl.modeview.BlockReads;
import com.memetrade.pnl.modeview.BlockResult;
import com.memetrade.pnl.modeview.ModeBlockExecutor;
import com.memetrade.pnl.modeview.ModePnlStatement;
import com.memetrade.pnl.modeview.ModePnlView;
import com.memetrade.pnl.modeview.PnlModeMetrics;
import com.memetrade.shared.web.CorrelationId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * 261121-paper-real-unico U1, Step 11, WRITTEN BEFORE THE CODE (BR5.1, BR5.2, BR5.3, NFR1.9, NFR3.12, NFR4.4, NFR4.6;
 * reliability-design.md "Isolamento entre os blocos de C4"): the view by mode puts two blocks side by side, each with
 * its own deadline in a bounded pool, and never adds them.
 *
 * <ul>
 *   <li>two blocks, each with its currency (USD and SOL), and no field of a general total;
 *   <li>a block that fails, times out or is refused by a full queue is UNAVAILABLE with the correlation id of the
 *       request, and the other block goes on; the metric counts the series of that market;
 *   <li>the deadline is checked again right after the transaction of the block opens;
 *   <li>the MDC of the request travels to the block, and a null MDC is cleared.
 * </ul>
 */
class ModePnlViewTest {

    private static final String REQUEST = "11111111-1111-1111-1111-111111111111";

    /** Transactions that open at once and never touch a database. */
    private static final BlockReads INSTANT = new BlockReads() {
        @Override
        public <T> T inTransaction(PnlMarket market, Callable<T> body) throws Exception {
            return body.call();
        }

        @Override
        public void statementTimeout(Duration left) {
            // no database in this test
        }
    };

    private SimpleMeterRegistry meters;
    private PnlModeMetrics metrics;
    private ModeBlockExecutor executor;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        metrics = new PnlModeMetrics(meters);
        MDC.put(CorrelationId.MDC_KEY, REQUEST);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        if (executor != null) {
            executor.destroy();
        }
    }

    @Test
    void twoBlocksComeSideBySideEachWithItsOwnValueAndThereIsNoThirdField() {
        ModePnlView view = view(PnlByModeProperties.defaults(), INSTANT);

        ModePnlStatement<String, String> statement = view.statement(() -> "PAPER-BLOCK", () -> "REAL-BLOCK");

        assertThat(statement.period()).isEqualTo("ALL");
        assertThat(statement.paper()).isEqualTo(new BlockResult.Available<>("PAPER-BLOCK"));
        assertThat(statement.real()).isEqualTo(new BlockResult.Available<>("REAL-BLOCK"));
        assertThat(Arrays.stream(ModePnlStatement.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("period", "paper", "real");
        assertThat(unavailable("PAPER")).isZero();
        assertThat(unavailable("REAL")).isZero();
    }

    @Test
    void aFailingRealBlockIsUnavailableInSolWithTheCorrelationIdAndThePaperBlockGoesOn() {
        ModePnlView view = view(PnlByModeProperties.defaults(), INSTANT);

        ModePnlStatement<String, String> statement = view.statement(() -> "PAPER-BLOCK", () -> {
            throw new IllegalStateException("the REAL failed");
        });

        assertThat(statement.paper()).isEqualTo(new BlockResult.Available<>("PAPER-BLOCK"));
        assertThat(statement.real())
                .isEqualTo(new BlockResult.Unavailable<>(PnlMarket.REAL, "SOL", "SERVICE_UNAVAILABLE", REQUEST));
        assertThat(unavailable("REAL")).isEqualTo(1);
        assertThat(unavailable("PAPER")).isZero();
    }

    @Test
    void aFailingPaperBlockIsUnavailableInUsdAndTheRealBlockGoesOn() {
        ModePnlView view = view(PnlByModeProperties.defaults(), INSTANT);

        ModePnlStatement<String, String> statement = view.statement(
                () -> {
                    throw new IllegalStateException("the PAPER failed");
                },
                () -> "REAL-BLOCK");

        assertThat(statement.paper())
                .isEqualTo(new BlockResult.Unavailable<>(PnlMarket.PAPER, "USD", "SERVICE_UNAVAILABLE", REQUEST));
        assertThat(statement.real()).isEqualTo(new BlockResult.Available<>("REAL-BLOCK"));
        assertThat(unavailable("PAPER")).isEqualTo(1);
    }

    @Test
    void bothFailingGiveTwoUnavailableBlocksAndCountBothSeries() {
        ModePnlView view = view(PnlByModeProperties.defaults(), INSTANT);

        ModePnlStatement<String, String> statement = view.statement(
                () -> {
                    throw new IllegalStateException("PAPER");
                },
                () -> {
                    throw new IllegalStateException("REAL");
                });

        assertThat(statement.paper()).isInstanceOf(BlockResult.Unavailable.class);
        assertThat(statement.real()).isInstanceOf(BlockResult.Unavailable.class);
        assertThat(unavailable("PAPER")).isEqualTo(1);
        assertThat(unavailable("REAL")).isEqualTo(1);
    }

    @Test
    void aBlockPastItsDeadlineIsUnavailableAndTheAnswerDoesNotWaitForIt() {
        ModePnlView view = view(new PnlByModeProperties(Duration.ofMillis(200), null, null, null), INSTANT);
        long started = System.nanoTime();

        ModePnlStatement<String, String> statement = view.statement(() -> "PAPER-BLOCK", () -> {
            Thread.sleep(2_000);
            return "late";
        });

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1_000));
        assertThat(statement.real()).isInstanceOf(BlockResult.Unavailable.class);
        assertThat(statement.paper()).isEqualTo(new BlockResult.Available<>("PAPER-BLOCK"));
    }

    @Test
    void aFullQueueRefusesTheBlockAtOnceAsUnavailableNeverAnError() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        executor = new ModeBlockExecutor(1, 0, INSTANT);
        ModePnlView view = new ModePnlView(executor, metrics, PnlByModeProperties.defaults());
        CountDownLatch busy = new CountDownLatch(1);
        executor.submit(
                PnlMarket.PAPER, System.nanoTime() + Duration.ofSeconds(10).toNanos(), () -> {
                    busy.countDown();
                    return release.await(10, TimeUnit.SECONDS);
                });
        assertThat(busy.await(5, TimeUnit.SECONDS)).isTrue();

        ModePnlStatement<String, String> statement = view.statement(() -> "PAPER-BLOCK", () -> "REAL-BLOCK");

        assertThat(statement.paper()).isInstanceOf(BlockResult.Unavailable.class);
        assertThat(statement.real()).isInstanceOf(BlockResult.Unavailable.class);
        release.countDown();
    }

    @Test
    void theDeadlineIsCheckedAgainRightAfterTheTransactionOpensAndTheBlockGivesUpWithoutReading() {
        AtomicBoolean read = new AtomicBoolean(false);
        BlockReads slowConnection = new BlockReads() {
            @Override
            public <T> T inTransaction(PnlMarket market, Callable<T> body) throws Exception {
                Thread.sleep(400);
                return body.call();
            }

            @Override
            public void statementTimeout(Duration left) {
                // no database in this test
            }
        };
        ModePnlView view = view(new PnlByModeProperties(Duration.ofMillis(200), null, null, null), slowConnection);

        ModePnlStatement<String, String> statement = view.statement(
                () -> {
                    read.set(true);
                    return "PAPER-BLOCK";
                },
                () -> "REAL-BLOCK");

        assertThat(statement.paper()).isInstanceOf(BlockResult.Unavailable.class);
        await(() -> !executorBusy());
        assertThat(read).isFalse();
    }

    @Test
    void theMdcOfTheRequestTravelsToTheBlockAndANullMdcIsCleared() throws Exception {
        executor = new ModeBlockExecutor(1, 4, INSTANT);
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        AtomicReference<String> seen = new AtomicReference<>("unset");

        String first = executor.submit(PnlMarket.PAPER, deadline, () -> MDC.get(CorrelationId.MDC_KEY))
                .get(5, TimeUnit.SECONDS);
        MDC.clear();
        assertThat(MDC.getCopyOfContextMap() == null
                        || MDC.getCopyOfContextMap().isEmpty())
                .isTrue();
        executor.submit(PnlMarket.PAPER, deadline, () -> {
                    seen.set(MDC.get(CorrelationId.MDC_KEY));
                    return Map.of();
                })
                .get(5, TimeUnit.SECONDS);

        assertThat(first).isEqualTo(REQUEST);
        assertThat(seen.get()).isNull();
    }

    @Test
    void theStatementTimeoutOfTheBlockIsWhatIsLeftOfTheDeadline() throws Exception {
        AtomicReference<Duration> applied = new AtomicReference<>();
        BlockReads recording = new BlockReads() {
            @Override
            public <T> T inTransaction(PnlMarket market, Callable<T> body) throws Exception {
                return body.call();
            }

            @Override
            public void statementTimeout(Duration left) {
                applied.set(left);
            }
        };
        executor = new ModeBlockExecutor(1, 1, recording);

        executor.submit(
                        PnlMarket.REAL,
                        System.nanoTime() + Duration.ofMillis(800).toNanos(),
                        () -> "ok")
                .get(5, TimeUnit.SECONDS);

        assertThat(applied.get()).isPositive().isLessThanOrEqualTo(Duration.ofMillis(800));
    }

    // ----- helpers -----

    private ModePnlView view(PnlByModeProperties properties, BlockReads transactions) {
        executor = new ModeBlockExecutor(properties.poolSize(), properties.queueCapacity(), transactions);
        return new ModePnlView(executor, metrics, properties);
    }

    /** 261121-paper-real-unico U2 (label {@code cause}): the sum of the 5 series of the market. */
    private double unavailable(String market) {
        return meters.find("pnl.mode_block.unavailable").tag("market", market).counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count)
                .sum();
    }

    private boolean executorBusy() {
        return executor.activeCount() > 0;
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < until) {
            Thread.onSpinWait();
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
