package com.memetrade.bots.worker;

import com.memetrade.bots.domain.Bot;
import com.memetrade.bots.domain.BotRef;
import com.memetrade.bots.infra.JdbcBotRepository;
import com.memetrade.bots.infra.JdbcCycleRepository;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.config.RecordedSwapFixture;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 261121-paper-real-unico U1 (BR10.1; review R-01): the TEST-ONLY first cycle of the test bots of the recorded swap, so
 * the e2e specs never wait for the 5-minute slot (a slow and unstable wait; team.md treats an unstable test as a
 * defect). EXISTS ONLY IN THE TEST PROFILE, in the worker with the scheduler of the cycle on, and acts only when the
 * {@link RecordedSwapFixture} exists (the REAL Compose with the recordings, {@code RECORDED_MINT} set) and only on an
 * ACTIVE bot that {@link RecordedSwapStrategy#applies} to (selection exactly the recorded mint) and that has NO cycle
 * yet. For that bot it runs, once, the same {@link CycleRunner#run} the slot dispatcher calls, for the CURRENT slot:
 *
 * <ul>
 *   <li>the same barrier, PAPER execution, REAL step, tenant and ledgers as any cycle; nothing is skipped;
 *   <li>the unique cycle per bot and slot stops a second run of the same slot (the dispatcher then finds it done);
 *   <li>a past slot is never run, and a bot of the product (any other selection) is never touched.
 * </ul>
 *
 * Production never has this bean: no {@code test} profile and no {@code RECORDED_MINT} outside the REAL Compose
 * ({@code RecordedSwapProfileGuardTest}).
 */
@Component
@Profile("test")
@ProcessRole(ProcessRole.Kind.WORKER)
@ConditionalOnProperty(name = "memetrade.bots.scheduler.enabled", havingValue = "true")
public class RecordedSwapFirstCycle {

    private static final Logger log = LoggerFactory.getLogger(RecordedSwapFirstCycle.class);

    private final Optional<RecordedSwapFixture> fixture;
    private final RecordedSwapStrategy strategy;
    private final JdbcBotRepository bots;
    private final JdbcCycleRepository cycles;
    private final CycleRunner runner;
    private final Clock clock;

    public RecordedSwapFirstCycle(
            Optional<RecordedSwapFixture> fixture,
            RecordedSwapStrategy strategy,
            JdbcBotRepository bots,
            JdbcCycleRepository cycles,
            CycleRunner runner,
            Clock clock) {
        this.fixture = fixture;
        this.strategy = strategy;
        this.bots = bots;
        this.cycles = cycles;
        this.runner = runner;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT10S", initialDelayString = "PT30S")
    public void tick() {
        runOnce();
    }

    /** One pass: the first cycle of every new test bot of the recorded swap, in the current slot. */
    public List<CycleResult> runOnce() {
        List<CycleResult> results = new ArrayList<>();
        if (fixture.isEmpty()) {
            return results;
        }
        for (BotRef ref : bots.activeBots()) {
            if (cycles.hasAnyCycle(ref.botId())) {
                continue;
            }
            Optional<Bot> bot = bots.find(ref.botId(), ref.scope());
            if (bot.isEmpty() || !strategy.applies(bot.get())) {
                continue;
            }
            CycleResult result = runner.run(ref, SlotDispatcher.slotOf(clock.instant()));
            log.info(
                    "TEST PROFILE: first cycle of a recorded swap test bot: botId={}, mode={}, result={}",
                    ref.botId(),
                    bot.get().real() ? "REAL" : "PAPER",
                    result.kind());
            results.add(result);
        }
        return results;
    }
}
