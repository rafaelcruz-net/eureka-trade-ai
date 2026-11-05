package com.memetrade.ledger.loadtest;

import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.api.PaperExecution.CloseResult;
import com.memetrade.ledger.api.PaperExecution.ExecutionResult;
import com.memetrade.ledger.loadtest.LoadtestLedgerSeed.Bot;
import com.memetrade.marketdata.api.TokenId;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * EXISTS ONLY WITH THE "loadtest" PROFILE: the load cycle driver of the scenarios {@code u7} and {@code u7-jobs}
 * (cicd-pipeline.md). Without U5 it plays the bot cycle: it waits until the expected number of people has signed in
 * and called a ledger route (their session principals, {@link LoadtestCycleAuthContext}), creates the bots
 * round-robin over them, funds them, opens one position per token, seeds the fills of each person and then runs one
 * cycle per bot every {@code cycle-interval} on its own threads (never the scheduler pool of the jobs), each in its own
 * cycle transaction calling {@code PaperExecution.execute}. The time of every cycle call is the timer
 * {@code loadtest.paper.execute} (Prometheus {@code loadtest_paper_execute_seconds}, NFR3.1). With
 * {@code close-bots} above 0 (scenario {@code u7}), that many bots are closed one per cycle interval from
 * {@code close-start} on, each {@code closePositions} timed in {@code loadtest.paper.close}.
 *
 * <p>With {@code memetrade.loadtest.ledger.people} at 0 (the default, and every scenario but u7 and u7-jobs) it does
 * nothing.
 */
@Component
@Profile("loadtest")
public class LoadtestCycleDriver {

    /** Where the preparation is; read by the health component {@code loadtestLedger}. */
    public enum State {
        DISABLED,
        WAITING_FOR_PEOPLE,
        WAITING_FOR_TOKENS,
        PREPARING,
        RUNNING,
        FAILED
    }

    private static final Logger log = LoggerFactory.getLogger(LoadtestCycleDriver.class);
    private static final Duration POLL = Duration.ofSeconds(1);
    private static final Duration TOKEN_WAIT = Duration.ofMinutes(10);

    private final LoadtestCycleAuthContext auth;
    private final LoadtestTokens tokens;
    private final LoadtestLedgerSeed seed;
    private final Timer executeTimer;
    private final Timer closeTimer;
    private final int people;
    private final long fillsPerPerson;
    private final int cycleBots;
    private final int tokensPerBot;
    private final Duration cycleInterval;
    private final int threads;
    private final int closeBots;
    private final Duration closeStart;
    private final AtomicLong cycles = new AtomicLong();
    private final AtomicLong cycleFailures = new AtomicLong();
    private final AtomicLong closes = new AtomicLong();
    private final AtomicLong closeFailures = new AtomicLong();
    private final Map<java.util.UUID, java.util.concurrent.ScheduledFuture<?>> cycleTasks =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile State state = State.DISABLED;
    private volatile String failure = "";
    private volatile List<Bot> bots = List.of();
    private volatile ScheduledExecutorService scheduler;
    private volatile Thread preparation;

    public LoadtestCycleDriver(
            LoadtestCycleAuthContext auth,
            LoadtestTokens tokens,
            LoadtestLedgerSeed seed,
            MeterRegistry registry,
            @Value("${memetrade.loadtest.ledger.people:0}") int people,
            @Value("${memetrade.loadtest.ledger.fills-per-person:0}") long fillsPerPerson,
            @Value("${memetrade.loadtest.ledger.cycle-bots:0}") int cycleBots,
            @Value("${memetrade.loadtest.ledger.tokens-per-bot:5}") int tokensPerBot,
            @Value("${memetrade.loadtest.ledger.cycle-interval:30s}") Duration cycleInterval,
            @Value("${memetrade.loadtest.ledger.threads:4}") int threads,
            @Value("${memetrade.loadtest.ledger.close-bots:0}") int closeBots,
            @Value("${memetrade.loadtest.ledger.close-start:120s}") Duration closeStart) {
        if (people < 0 || fillsPerPerson < 0 || cycleBots < 0 || tokensPerBot < 1 || threads < 1 || closeBots < 0) {
            throw new IllegalStateException("memetrade.loadtest.ledger values must not be negative");
        }
        if (people > 0 && cycleBots < people) {
            throw new IllegalStateException("memetrade.loadtest.ledger.cycle-bots must cover every person");
        }
        if (closeBots > cycleBots) {
            throw new IllegalStateException("memetrade.loadtest.ledger.close-bots must not exceed cycle-bots");
        }
        this.auth = auth;
        this.tokens = tokens;
        this.seed = seed;
        this.executeTimer = Timer.builder("loadtest.paper.execute")
                .description("Time of PaperExecution.execute in one cycle transaction of the load driver (NFR3.1)")
                .publishPercentileHistogram()
                .serviceLevelObjectives(Duration.ofMillis(250))
                .register(registry);
        this.closeTimer = Timer.builder("loadtest.paper.close")
                .description(
                        "Time of PaperExecution.closePositions in one cycle transaction of the load driver (NFR3.1)")
                .publishPercentileHistogram()
                .serviceLevelObjectives(Duration.ofMillis(250))
                .register(registry);
        this.closeBots = closeBots;
        this.closeStart = closeStart;
        this.people = people;
        this.fillsPerPerson = fillsPerPerson;
        this.cycleBots = cycleBots;
        this.tokensPerBot = tokensPerBot;
        this.cycleInterval = cycleInterval;
        this.threads = threads;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (people == 0) {
            log.info("Load cycle driver disabled: memetrade.loadtest.ledger.people is 0");
            return;
        }
        log.warn("LOADTEST PROFILE ACTIVE: load cycle driver waiting for {} people", people);
        state = State.WAITING_FOR_PEOPLE;
        preparation = new Thread(this::prepareAndRun, "loadtest-ledger-preparation");
        preparation.setDaemon(true);
        preparation.start();
    }

    private void prepareAndRun() {
        try {
            List<Principal> seen = awaitPeople();
            state = State.WAITING_FOR_TOKENS;
            List<TokenId> traded = awaitTokens();
            state = State.PREPARING;
            List<Bot> prepared = prepare(seen, traded);
            startCycles(prepared);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail("interrupted");
        } catch (RuntimeException | ExecutionException failed) {
            log.error("Load cycle driver failed", failed);
            fail(failed.getClass().getSimpleName() + ": " + failed.getMessage());
        }
    }

    /**
     * Funds the bots and opens their positions, then seeds the fills of every person on {@code threads} threads.
     *
     * @return the prepared bots
     */
    public List<Bot> prepare(List<Principal> seen, List<TokenId> traded)
            throws InterruptedException, ExecutionException {
        List<Bot> created = seed.bots(seen, cycleBots, traded);
        bots = created;
        Map<Principal, List<Bot>> byPerson = created.stream()
                .collect(Collectors.groupingBy(Bot::owner, java.util.LinkedHashMap::new, Collectors.toList()));
        ExecutorService pool = Executors.newFixedThreadPool(threads, named("loadtest-ledger-seed"));
        try {
            List<Future<?>> work = new ArrayList<>();
            for (List<Bot> personBots : byPerson.values()) {
                work.add(pool.submit(() -> {
                    personBots.forEach(seed::fundAndOpen);
                    seed.seedFills(personBots, fillsPerPerson);
                    log.info("Load ledger seed: one person done, fills so far {}", seed.fillsRecorded());
                    return null;
                }));
            }
            for (Future<?> done : work) {
                done.get();
            }
        } finally {
            pool.shutdownNow();
        }
        log.info(
                "Load ledger prepared: people={}, bots={}, fills={}",
                byPerson.size(),
                created.size(),
                seed.fillsRecorded());
        return created;
    }

    /** One cycle of {@code bot}, timed; a failure is counted and logged with identifiers only, never swallowed. */
    public void cycle(Bot bot) {
        long started = System.nanoTime();
        try {
            ExecutionResult result = seed.cycle(bot);
            executeTimer.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            cycles.incrementAndGet();
            if (result instanceof ExecutionResult.NotFilled notFilled) {
                log.debug("Load cycle not filled: botId={}, reason={}", bot.botId(), notFilled.reason());
            }
        } catch (RuntimeException failed) {
            cycleFailures.incrementAndGet();
            log.warn(
                    "Load cycle failed: botId={}, error={}",
                    bot.botId(),
                    failed.getClass().getSimpleName());
        }
    }

    /**
     * Closes {@code bot} as U5 will close a deactivated bot (review repair R-04): its cycles stop first (a cycle already
     * running finishes), then {@code closePositions} runs in one cycle transaction, timed in
     * {@code loadtest.paper.close} (Prometheus {@code loadtest_paper_close_seconds}, NFR3.1). A failure is counted and
     * logged with identifiers only, never swallowed.
     */
    public void closeBot(Bot bot) {
        java.util.concurrent.ScheduledFuture<?> task = cycleTasks.remove(bot.botId());
        if (task != null) {
            task.cancel(false);
        }
        long started = System.nanoTime();
        try {
            CloseResult result = seed.close(bot);
            closeTimer.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            closes.incrementAndGet();
            if (!(result instanceof CloseResult.Closed)) {
                log.warn(
                        "Load close not completed: botId={}, result={}",
                        bot.botId(),
                        result.getClass().getSimpleName());
            }
        } catch (RuntimeException failed) {
            closeFailures.incrementAndGet();
            log.warn(
                    "Load close failed: botId={}, error={}",
                    bot.botId(),
                    failed.getClass().getSimpleName());
        }
    }

    private void startCycles(List<Bot> prepared) {
        ScheduledExecutorService cyclesPool = Executors.newScheduledThreadPool(threads, named("loadtest-ledger-cycle"));
        long spacing = cycleInterval.toMillis() / Math.max(1, prepared.size());
        for (int i = 0; i < prepared.size(); i++) {
            Bot bot = prepared.get(i);
            cycleTasks.put(
                    bot.botId(),
                    cyclesPool.scheduleAtFixedRate(
                            () -> cycle(bot), i * spacing, cycleInterval.toMillis(), TimeUnit.MILLISECONDS));
        }
        // The first close-bots bots belong to different people (round-robin); one closes every cycle interval from
        // close-start on, while the other bots keep cycling and the readers keep reading.
        for (int i = 0; i < Math.min(closeBots, prepared.size()); i++) {
            Bot bot = prepared.get(i);
            cyclesPool.schedule(
                    () -> closeBot(bot), closeStart.toMillis() + i * cycleInterval.toMillis(), TimeUnit.MILLISECONDS);
        }
        scheduler = cyclesPool;
        state = State.RUNNING;
        log.info(
                "Load cycle driver running: bots={}, interval={}, botsToClose={}",
                prepared.size(),
                cycleInterval,
                Math.min(closeBots, prepared.size()));
    }

    private List<Principal> awaitPeople() throws InterruptedException {
        long waited = 0;
        while (auth.people().size() < people) {
            if (waited % 30 == 0) {
                log.info(
                        "Load cycle driver waiting for people: seen={}, expected={}",
                        auth.people().size(),
                        people);
            }
            Thread.sleep(POLL.toMillis());
            waited++;
        }
        return auth.people().subList(0, people);
    }

    private List<TokenId> awaitTokens() throws InterruptedException {
        long deadline = System.nanoTime() + TOKEN_WAIT.toNanos();
        List<TokenId> found = tokens.tradable(tokensPerBot);
        while (found.size() < tokensPerBot) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("Fewer tradable simulated tokens than tokens-per-bot after 10 minutes");
            }
            Thread.sleep(POLL.toMillis() * 5);
            found = tokens.tradable(tokensPerBot);
        }
        return found;
    }

    private void fail(String reason) {
        failure = reason;
        state = State.FAILED;
    }

    private static java.util.concurrent.ThreadFactory named(String prefix) {
        AtomicLong counter = new AtomicLong();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
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

    public int expectedPeople() {
        return people;
    }

    public long expectedFills() {
        return people * fillsPerPerson;
    }

    public int bots() {
        return bots.size();
    }

    /**
     * U6 (scenario {@code u6}): the funded bots of the load and their owners, once the preparation is over; the load
     * cycle driver of RiskControl evaluates orders of these same bots. Empty before {@link State#RUNNING}.
     */
    public List<Bot> preparedBots() {
        return state == State.RUNNING ? bots : List.of();
    }

    public long cycles() {
        return cycles.get();
    }

    public long cycleFailures() {
        return cycleFailures.get();
    }

    public int closeBots() {
        return closeBots;
    }

    public long closes() {
        return closes.get();
    }

    public long closeFailures() {
        return closeFailures.get();
    }

    public long fillsRecorded() {
        return seed.fillsRecorded();
    }

    public int peopleSeen() {
        return auth.people().size();
    }
}
