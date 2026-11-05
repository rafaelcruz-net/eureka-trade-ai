package com.memetrade.bots.worker;

import com.memetrade.bots.domain.BotRef;
import com.memetrade.bots.infra.BotsConfig;
import com.memetrade.bots.infra.JdbcBotRepository;
import com.memetrade.shared.config.ProcessRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * BR3.1, NFR10.3 (scalability-design.md): opens the 5-minute UTC slot, 60 s after its start, ONCE across instances
 * (ShedLock {@code bots-slot-dispatch}, held at least 4 and at most 5 minutes, so a second worker started by mistake
 * cannot dispatch the same slot; even if it did, the unique cycle per bot and slot would stop a duplicate). Every
 * ACTIVE bot goes to the executor of {@code cycle-threads} (4); the round waits for all of them. After a restart, only
 * the current slot is dispatched: missed slots are never run late.
 */
@Component
@ProcessRole(ProcessRole.Kind.WORKER)
public class SlotDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SlotDispatcher.class);

    static final String LOCK_NAME = "bots-slot-dispatch";
    static final Duration LOCK_AT_LEAST = Duration.ofMinutes(4);
    static final Duration LOCK_AT_MOST = Duration.ofMinutes(5);
    private static final long SLOT_SECONDS = 300;

    private final JdbcBotRepository bots;
    private final CycleRunner cycles;
    private final LockingTaskExecutor locks;
    private final ThreadPoolTaskExecutor executor;
    private final Clock clock;

    public SlotDispatcher(
            JdbcBotRepository bots,
            CycleRunner cycles,
            LockingTaskExecutor locks,
            @Qualifier(BotsConfig.CYCLE_EXECUTOR) ThreadPoolTaskExecutor executor,
            Clock clock) {
        this.bots = bots;
        this.cycles = cycles;
        this.locks = locks;
        this.executor = executor;
        this.clock = clock;
    }

    /** The result of one round: whether it ran, the slot and how many cycles ended in each way. */
    public record Round(boolean ran, Instant slotStart, Map<CycleResult.Kind, Integer> results) {}

    /** The start of the slot of {@code instant}: truncated to the previous multiple of 5 minutes (UTC). */
    public static Instant slotOf(Instant instant) {
        long seconds = instant.getEpochSecond();
        return Instant.ofEpochSecond(seconds - Math.floorMod(seconds, SLOT_SECONDS));
    }

    public Round runSlot() {
        Instant now = clock.instant();
        Instant slot = slotOf(now);
        LockConfiguration lock = new LockConfiguration(now, LOCK_NAME, LOCK_AT_MOST, LOCK_AT_LEAST);
        try {
            LockingTaskExecutor.TaskResult<Round> result = locks.executeWithLock(() -> dispatch(slot), lock);
            if (!result.wasExecuted()) {
                log.info("Slot dispatch skipped: another worker holds the slot {}", slot);
                return new Round(false, slot, Map.of());
            }
            return result.getResult();
        } catch (RuntimeException | Error unexpected) {
            throw unexpected;
        } catch (Throwable checked) {
            throw new IllegalStateException("The slot dispatch failed with a checked exception", checked);
        }
    }

    private Round dispatch(Instant slot) {
        List<BotRef> active = bots.activeBots();
        List<CompletableFuture<CycleResult>> futures = new ArrayList<>();
        for (BotRef bot : active) {
            futures.add(CompletableFuture.supplyAsync(() -> cycles.run(bot, slot), executor));
        }
        Map<CycleResult.Kind, Integer> results = new EnumMap<>(CycleResult.Kind.class);
        for (CompletableFuture<CycleResult> future : futures) {
            try {
                results.merge(future.join().kind(), 1, Integer::sum);
            } catch (java.util.concurrent.CompletionException failure) {
                log.error(
                        "A cycle of the slot could not record its failure: error={}",
                        failure.getCause() == null
                                ? "unknown"
                                : failure.getCause().getClass().getSimpleName());
                results.merge(CycleResult.Kind.FAILED, 1, Integer::sum);
            }
        }
        log.info("Slot dispatched: slotStart={}, bots={}, results={}", slot, active.size(), results);
        return new Round(true, slot, results);
    }
}
