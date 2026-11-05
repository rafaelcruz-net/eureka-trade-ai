package com.memetrade.bots.loadtest;

import com.memetrade.bots.application.BotCommandService;
import com.memetrade.bots.domain.Bot;
import com.memetrade.bots.domain.BotRef;
import com.memetrade.bots.domain.TenantScope;
import com.memetrade.bots.infra.JdbcBotRepository;
import com.memetrade.bots.infra.JdbcCycleRepository;
import com.memetrade.decision.domain.DecisionAction;
import com.memetrade.decision.domain.DecisionOutcome;
import com.memetrade.decision.domain.NoTradeReason;
import com.memetrade.decision.infra.DecisionJson;
import com.memetrade.decision.infra.JdbcDecisionRepository;
import com.memetrade.decision.infra.JdbcDecisionRepository.NewDecision;
import com.memetrade.shared.config.ProcessRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * EXISTS ONLY WITH THE "loadtest" PROFILE, in the api: the preparation of the load scenario {@code u5}
 * (cicd-pipeline.md). The bots themselves are made by {@code scripts/load/load-test.mjs} through the REAL C4 routes
 * (create, the wizard steps and start, so every PAPER account is funded by the real U7); this seed waits until
 * {@code people x bots-per-person} bots are ACTIVE and then writes {@code decisions-per-bot} recorded decisions
 * (NO_TRADE / NO_SIGNAL, one cycle per past minute, so no slot repeats) on the first bot of each person, in batches of
 * {@value #BATCH} per transaction, through the repositories of U5. Health component {@code loadtestBots}: UP when the
 * preparation is over. Synthetic values only; never measured.
 */
@Component("loadtestBots")
@Profile("loadtest")
@ProcessRole(ProcessRole.Kind.API)
public class LoadtestBotSeed implements HealthIndicator {

    static final int BATCH = 1000;

    /** Where the preparation is, as the health component shows it. */
    public enum State {
        DISABLED,
        WAITING_FOR_BOTS,
        SEEDING,
        READY,
        FAILED
    }

    private static final Logger log = LoggerFactory.getLogger(LoadtestBotSeed.class);
    private static final Duration POLL = Duration.ofSeconds(5);
    private static final Duration WAIT = Duration.ofMinutes(30);

    private final JdbcBotRepository bots;
    private final JdbcCycleRepository cycles;
    private final JdbcDecisionRepository decisions;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final boolean enabled;
    private final int people;
    private final int botsPerPerson;
    private final long decisionsPerBot;
    private final AtomicLong seeded = new AtomicLong();
    private volatile State state = State.DISABLED;
    private volatile String failure = "";
    private volatile int activeSeen;

    public LoadtestBotSeed(
            JdbcBotRepository bots,
            JdbcCycleRepository cycles,
            JdbcDecisionRepository decisions,
            PlatformTransactionManager transactionManager,
            Clock clock,
            @Value("${memetrade.loadtest.bots.enabled:false}") boolean enabled,
            @Value("${memetrade.loadtest.bots.people:20}") int people,
            @Value("${memetrade.loadtest.bots.bots-per-person:3}") int botsPerPerson,
            @Value("${memetrade.loadtest.bots.decisions-per-bot:20000}") long decisionsPerBot) {
        if (people < 1 || botsPerPerson < 1 || botsPerPerson > 3 || decisionsPerBot < 0) {
            throw new IllegalStateException(
                    "memetrade.loadtest.bots: people >= 1, bots-per-person 1 to 3, decisions-per-bot >= 0");
        }
        this.bots = bots;
        this.cycles = cycles;
        this.decisions = decisions;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.enabled = enabled;
        this.people = people;
        this.botsPerPerson = botsPerPerson;
        this.decisionsPerBot = decisionsPerBot;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) {
            log.info("Load bot seed disabled: memetrade.loadtest.bots.enabled is false");
            return;
        }
        log.warn("LOADTEST PROFILE ACTIVE: load bot seed waiting for {} ACTIVE bots", people * botsPerPerson);
        state = State.WAITING_FOR_BOTS;
        Thread preparation = new Thread(this::prepare, "loadtest-bots-preparation");
        preparation.setDaemon(true);
        preparation.start();
    }

    private void prepare() {
        try {
            List<BotRef> active = awaitBots();
            state = State.SEEDING;
            for (BotRef bot : firstBotOfEachPerson(active).values()) {
                seedDecisions(bot);
            }
            state = State.READY;
            log.info("Load bot seed ready: people={}, decisions={}", people, seeded.get());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            fail("interrupted");
        } catch (RuntimeException failed) {
            log.error("Load bot seed failed", failed);
            fail(failed.getClass().getSimpleName() + ": " + failed.getMessage());
        }
    }

    private List<BotRef> awaitBots() throws InterruptedException {
        long waited = 0;
        while (waited < WAIT.toMillis()) {
            List<BotRef> active = bots.activeBots();
            activeSeen = active.size();
            if (active.size() >= people * botsPerPerson) {
                return active;
            }
            Thread.sleep(POLL.toMillis());
            waited += POLL.toMillis();
        }
        throw new IllegalStateException("fewer than " + people * botsPerPerson + " ACTIVE bots after " + WAIT);
    }

    /** The first bot (lowest id) of each person; the order of {@link JdbcBotRepository#activeBots} is by id. */
    static Map<TenantScope, BotRef> firstBotOfEachPerson(List<BotRef> active) {
        Map<TenantScope, BotRef> first = new LinkedHashMap<>();
        active.stream()
                .sorted(java.util.Comparator.comparing(BotRef::botId))
                .forEach(bot -> first.putIfAbsent(bot.scope(), bot));
        return first;
    }

    void seedDecisions(BotRef ref) {
        Bot bot = bots.find(ref.botId(), ref.scope()).orElseThrow();
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        DecisionJson.Recorded recorded = new DecisionJson.Recorded("{}", "[]", "{}", "[]");
        for (long done = 0; done < decisionsPerBot; done += BATCH) {
            long size = Math.min(BATCH, decisionsPerBot - done);
            List<NewDecision> batch = new ArrayList<>();
            for (long i = 0; i < size; i++) {
                Instant at = now.minus(Duration.ofMinutes(done + i + 1));
                DecisionOutcome outcome = new DecisionOutcome(
                        DecisionAction.NO_TRADE,
                        Optional.of(BotCommandService.NEUTRAL_TOKEN),
                        Optional.of(NoTradeReason.NO_SIGNAL),
                        Optional.empty(),
                        Optional.empty(),
                        List.of(),
                        "Carga simulada: nenhum sinal.",
                        at.plus(Duration.ofMinutes(5)));
                batch.add(new NewDecision(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        ref.botId(),
                        ref.scope().tenantId(),
                        ref.scope().ownerUserId(),
                        bot.templateId(),
                        bot.templateVersion(),
                        bot.currentLimitsVersion().orElse(1),
                        outcome,
                        recorded,
                        at));
            }
            transactions.executeWithoutResult(status -> batch.forEach(decision -> {
                cycles.tryStart(decision.cycleId(), ref, decision.createdAt(), decision.createdAt());
                cycles.finish(
                        decision.cycleId(),
                        JdbcCycleRepository.Status.COMPLETED,
                        Optional.of("NO_ORDER"),
                        Optional.empty(),
                        Optional.of(decision.decisionId()),
                        decision.createdAt());
                decisions.insert(decision);
            }));
            seeded.addAndGet(size);
        }
    }

    private void fail(String reason) {
        failure = reason;
        state = State.FAILED;
    }

    @Override
    public Health health() {
        // UNKNOWN until ready (like loadtestLedger of U7): it never lowers the aggregate health of the api.
        Health.Builder builder = state == State.READY || state == State.DISABLED ? Health.up() : Health.unknown();
        return builder.withDetail("state", state.name())
                .withDetail("failure", failure)
                .withDetail("activeBots", activeSeen)
                .withDetail("botsExpected", people * botsPerPerson)
                .withDetail("decisionsSeeded", seeded.get())
                .withDetail("decisionsExpected", people * decisionsPerBot)
                .build();
    }

    State state() {
        return state;
    }
}
