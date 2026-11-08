package com.memetrade.riskcontrol.loadtest;

import com.memetrade.ledger.loadtest.LoadtestCycleAuthContext;
import com.memetrade.ledger.loadtest.LoadtestCycleDriver;
import com.memetrade.ledger.loadtest.LoadtestLedgerSeed.Bot;
import com.memetrade.ledger.loadtest.LoadtestTokens;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.riskcontrol.api.BotLimitsVersionRef;
import com.memetrade.riskcontrol.api.CandidateOrder;
import com.memetrade.riskcontrol.api.GateResult;
import com.memetrade.riskcontrol.api.OrderGate;
import com.memetrade.riskcontrol.api.OrderSide;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * EXISTS ONLY WITH THE "loadtest" PROFILE: the load cycle driver of the scenario {@code u6} (cicd-pipeline.md). Without
 * U5 it plays the barrier part of the bot cycle over the PAPER accounts the ledger load prepared (FUNDING, no seeded
 * fills): it waits for the driver of U7 to be running, seeds the volume of U6 ({@link LoadtestRiskSeed}) and then runs
 * one evaluation per bot every {@code cycle-interval} on its own threads, each in a cycle transaction in READ COMMITTED
 * with the owner of the bot bound in the authentication context, calling {@link OrderGate#evaluate}. Every other cycle
 * uses limits so tight that the order is refused (half of the evaluations), the others wide limits. The time of each
 * call is the timer {@code loadtest.risk.evaluate} (Prometheus {@code loadtest_risk_evaluate_seconds}, NFR2.1), with
 * the label {@code window} {@code before-purge} or {@code with-purge}; at {@code purge-at} the purge fires once.
 *
 * <p>With {@code memetrade.loadtest.risk.enabled} false (the default, every scenario but u6) it does nothing.
 */
@Component
@Profile("loadtest")
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class LoadtestRiskCycleDriver {

    /** Where the preparation is; read by the health component {@code loadtestRisk}. */
    public enum State {
        DISABLED,
        WAITING_FOR_LEDGER,
        SEEDING,
        RUNNING,
        FAILED
    }

    private static final Logger log = LoggerFactory.getLogger(LoadtestRiskCycleDriver.class);
    private static final Duration POLL = Duration.ofSeconds(1);
    private static final Duration LEDGER_WAIT = Duration.ofMinutes(120);
    static final int MAX_SAMPLES_PER_WINDOW = 200_000;

    /** Limits every order fits in: the cycle is approved unless the market or the risk view refuses it. */
    static final BotLimitsVersionRef WIDE = limits("1000000.000000", "100.0000", "0.000000", 0);

    /** Limits no order fits in (order size): the cycle is refused with ORDER_SIZE. */
    static final BotLimitsVersionRef TIGHT = limits("0.000001", "100.0000", "0.000000", 0);

    private final LoadtestCycleDriver ledger;
    private final LoadtestCycleAuthContext auth;
    private final LoadtestTokens tokens;
    private final LoadtestRiskSeed seed;
    private final LoadtestRiskPurgeTrigger purge;
    private final OrderGate gate;
    private final TransactionTemplate cycles;
    private final MeterRegistry registry;
    private final Clock clock;
    private final boolean enabled;
    private final long rejectionsPerBot;
    private final long heavyBotRejections;
    private final long oldApproved;
    private final Duration purgeAt;
    private final Duration cycleInterval;
    private final int threads;
    private final AtomicLong evaluations = new AtomicLong();
    private final AtomicLong approved = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong skipped = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final Map<String, List<Long>> samples = new ConcurrentHashMap<>();
    private volatile State state = State.DISABLED;
    private volatile String failure = "";
    private volatile Instant cyclesStartedAt;
    private volatile ScheduledExecutorService scheduler;
    private volatile Thread preparation;

    public LoadtestRiskCycleDriver(
            LoadtestCycleDriver ledger,
            LoadtestCycleAuthContext auth,
            LoadtestTokens tokens,
            LoadtestRiskSeed seed,
            LoadtestRiskPurgeTrigger purge,
            OrderGate gate,
            PlatformTransactionManager transactionManager,
            MeterRegistry registry,
            Clock clock,
            @Value("${memetrade.loadtest.risk.enabled:false}") boolean enabled,
            @Value("${memetrade.loadtest.risk.rejections-per-bot:10000}") long rejectionsPerBot,
            @Value("${memetrade.loadtest.risk.heavy-bot-rejections:80000}") long heavyBotRejections,
            @Value("${memetrade.loadtest.risk.old-approved:200000}") long oldApproved,
            @Value("${memetrade.loadtest.risk.purge-at:300s}") Duration purgeAt,
            @Value("${memetrade.loadtest.risk.cycle-interval:30s}") Duration cycleInterval,
            @Value("${memetrade.loadtest.risk.threads:6}") int threads) {
        if (rejectionsPerBot < 0 || heavyBotRejections < 0 || oldApproved < 0 || threads < 1) {
            throw new IllegalStateException("memetrade.loadtest.risk values must not be negative");
        }
        this.ledger = ledger;
        this.auth = auth;
        this.tokens = tokens;
        this.seed = seed;
        this.purge = purge;
        this.gate = gate;
        this.cycles = new TransactionTemplate(transactionManager);
        this.cycles.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.registry = registry;
        this.clock = clock;
        this.enabled = enabled;
        this.rejectionsPerBot = rejectionsPerBot;
        this.heavyBotRejections = heavyBotRejections;
        this.oldApproved = oldApproved;
        this.purgeAt = purgeAt;
        this.cycleInterval = cycleInterval;
        this.threads = threads;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) {
            log.info("Load risk cycle driver disabled: memetrade.loadtest.risk.enabled is false");
            return;
        }
        log.warn("LOADTEST PROFILE ACTIVE: load risk cycle driver waiting for the ledger load");
        state = State.WAITING_FOR_LEDGER;
        preparation = new Thread(this::prepareAndRun, "loadtest-risk-preparation");
        preparation.setDaemon(true);
        preparation.start();
    }

    private void prepareAndRun() {
        try {
            List<Bot> bots = awaitLedger();
            state = State.SEEDING;
            seed.seed(bots, rejectionsPerBot, heavyBotRejections, oldApproved);
            log.info(
                    "Load risk seed done: rejections={}, oldApproved={}",
                    seed.rejectionsSeeded(),
                    seed.oldApprovedSeeded());
            startCycles(bots);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail("interrupted");
        } catch (RuntimeException failed) {
            log.error("Load risk cycle driver failed", failed);
            fail(failed.getClass().getSimpleName() + ": " + failed.getMessage());
        }
    }

    /** One evaluation of {@code bot}, timed in the window it runs in; failures are counted and logged with ids only. */
    public void cycle(Bot bot, long turn) {
        TokenId token = bot.tokens().get((int) (turn % bot.tokens().size()));
        Optional<BigDecimal> quantity = tokens.quantity(token);
        if (quantity.isEmpty()) {
            skipped.incrementAndGet();
            return;
        }
        CandidateOrder order = new CandidateOrder(
                bot.botId(), UUID.randomUUID(), OrderSide.BUY, token.chain(), token.mint(), quantity.get());
        BotLimitsVersionRef limits = turn % 2 == 0 ? WIDE : TIGHT;
        long started = System.nanoTime();
        try {
            GateResult result = auth.as(bot.owner(), () -> cycles.execute(status -> gate.evaluate(order, limits)));
            long elapsed = System.nanoTime() - started;
            String window = window();
            timer(window).record(elapsed, TimeUnit.NANOSECONDS);
            sample(window, elapsed);
            evaluations.incrementAndGet();
            if (result instanceof GateResult.Approved) {
                approved.incrementAndGet();
            } else {
                rejected.incrementAndGet();
            }
        } catch (RuntimeException failed) {
            failures.incrementAndGet();
            log.warn(
                    "Load risk cycle failed: botId={}, error={}",
                    bot.botId(),
                    failed.getClass().getSimpleName());
        }
    }

    private void startCycles(List<Bot> bots) {
        ScheduledExecutorService pool = Executors.newScheduledThreadPool(threads, named());
        long spacing = cycleInterval.toMillis() / Math.max(1, bots.size());
        for (int i = 0; i < bots.size(); i++) {
            Bot bot = bots.get(i);
            AtomicLong turn = new AtomicLong(i);
            pool.scheduleAtFixedRate(
                    () -> cycle(bot, turn.getAndIncrement()),
                    i * spacing,
                    cycleInterval.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
        pool.schedule(purge::fire, purgeAt.toMillis(), TimeUnit.MILLISECONDS);
        scheduler = pool;
        cyclesStartedAt = clock.instant();
        state = State.RUNNING;
        log.info(
                "Load risk cycle driver running: bots={}, interval={}, purgeAt={}",
                bots.size(),
                cycleInterval,
                purgeAt);
    }

    private List<Bot> awaitLedger() throws InterruptedException {
        long deadline = System.nanoTime() + LEDGER_WAIT.toNanos();
        while (ledger.state() != LoadtestCycleDriver.State.RUNNING) {
            if (ledger.state() == LoadtestCycleDriver.State.FAILED) {
                throw new IllegalStateException("The ledger load failed: " + ledger.failure());
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("The ledger load did not start in 120 minutes");
            }
            Thread.sleep(POLL.toMillis());
        }
        List<Bot> bots = ledger.preparedBots();
        if (bots.isEmpty()) {
            throw new IllegalStateException("The ledger load has no bot (LOADTEST_CYCLE_BOTS is 0)");
        }
        return bots;
    }

    /** The window of the scenario a call belongs to: before the purge fired, or with the purge running. */
    private String window() {
        Instant started = cyclesStartedAt;
        boolean beforePurge = started == null || clock.instant().isBefore(started.plus(purgeAt));
        return beforePurge ? "before-purge" : "with-purge";
    }

    /**
     * Build and Test loop 3 (F12, NFR2.1): the time of every call, in whole microseconds rounded up, by window, so the
     * script computes the p95 of each window from the samples instead of from a bucket of the histogram (20% to 33%
     * wide between 10 and 60 ms). At most {@link #MAX_SAMPLES_PER_WINDOW} per window (a run of u6 makes about 2000).
     */
    private void sample(String window, long elapsedNanos) {
        List<Long> list = samples.computeIfAbsent(window, name -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (list) {
            if (list.size() < MAX_SAMPLES_PER_WINDOW) {
                list.add(Math.ceilDiv(elapsedNanos, 1_000L));
            }
        }
    }

    /** The samples of each window in microseconds, in the order recorded (a copy). */
    public Map<String, List<Long>> evaluateMicros() {
        Map<String, List<Long>> copy = new TreeMap<>();
        samples.forEach((window, list) -> {
            synchronized (list) {
                copy.put(window, List.copyOf(list));
            }
        });
        return copy;
    }

    private Timer timer(String window) {
        return Timer.builder("loadtest.risk.evaluate")
                .description("Time of OrderGate.evaluate in one cycle transaction of the load driver (NFR2.1)")
                .tag("window", window)
                .publishPercentileHistogram()
                .serviceLevelObjectives(Duration.ofMillis(250))
                .register(registry);
    }

    private void fail(String reason) {
        failure = reason;
        state = State.FAILED;
    }

    private static java.util.concurrent.ThreadFactory named() {
        AtomicLong counter = new AtomicLong();
        return runnable -> {
            Thread thread = new Thread(runnable, "loadtest-risk-cycle-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static BotLimitsVersionRef limits(
            String maxOrderSize, String maxSlippage, String minLiquidity, long interval) {
        return new BotLimitsVersionRef(
                1,
                new BigDecimal(maxOrderSize),
                new BigDecimal("1000000.000000"),
                new BigDecimal("1000000.000000"),
                new BigDecimal(maxSlippage),
                new BigDecimal(minLiquidity),
                interval,
                new BigDecimal("1000000.000000"),
                new BigDecimal("100.0000"));
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (preparation != null) {
            preparation.interrupt();
        }
    }

    public State state() {
        return state;
    }

    public String failure() {
        return failure;
    }

    public Optional<Instant> cyclesStartedAt() {
        return Optional.ofNullable(cyclesStartedAt);
    }

    public long purgeAtSeconds() {
        return purgeAt.toSeconds();
    }

    public long evaluations() {
        return evaluations.get();
    }

    public long approved() {
        return approved.get();
    }

    public long rejected() {
        return rejected.get();
    }

    public long skipped() {
        return skipped.get();
    }

    public long failures() {
        return failures.get();
    }

    public long rejectionsSeeded() {
        return seed.rejectionsSeeded();
    }

    public long oldApprovedSeeded() {
        return seed.oldApprovedSeeded();
    }
}
