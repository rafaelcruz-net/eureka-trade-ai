package com.memetrade.testsupport;

import com.memetrade.bots.api.BotAdministration;
import com.memetrade.bots.api.BotState;
import com.memetrade.identity.api.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TEST-ONLY double of C16 (unit-test-instructions.md "Mocks e dublês") used by the U8 integration tests. Reads
 * ({@link #countByState()}, {@link #summaryFor}) always go to the REAL U5. The system pause has two modes:
 *
 * <ul>
 *   <li>{@link Mode#FAKE} (default): an in-memory fleet of synthetic bots with the SYSTEM reason and the U5 copy of the
 *       kill switch, so a test controls failures per bot, an interruption after N bots (the "crash in the middle of the
 *       batches"), an exception, and a pause in the middle of the call; the real bots and the real
 *       {@code system_pause_state} of the shared database are never touched;
 *   <li>{@link Mode#REAL}: every call goes to the real U5 ({@code KillSwitchRaceIT} and the performance test); the base
 *       class lifts the real pause after the test.
 * </ul>
 *
 * Every call is recorded in order ({@link #calls()}), so a test can check which operation reached U5.
 */
public final class ControllableBotAdministration implements BotAdministration {

    /** Where the system pause goes. */
    public enum Mode {
        FAKE,
        REAL
    }

    /** One call of the system pause that reached the double. */
    public record Call(String operation, String correlationId) {}

    private final BotAdministration real;
    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.FAKE);
    private final AtomicBoolean realUsed = new AtomicBoolean();

    private final Map<UUID, Boolean> fleet = new ConcurrentHashMap<>();
    private final Set<UUID> failing = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean systemPaused = new AtomicBoolean();
    private final AtomicInteger crashAfter = new AtomicInteger(-1);
    private final AtomicInteger exceptionsLeft = new AtomicInteger();
    private final AtomicReference<CountDownLatch> entered = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> release = new AtomicReference<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final AtomicReference<Runnable> beforeEachCall = new AtomicReference<>();

    /** Runs {@code hook} at the start of every call of the system pause (for example to change the version). */
    public void beforeEachCall(Runnable hook) {
        beforeEachCall.set(hook);
    }

    public ControllableBotAdministration(BotAdministration real) {
        this.real = real;
    }

    /** Back to the default: FAKE, empty fleet, no failure, U5 copy OFF, no recorded call. */
    public void reset() {
        mode.set(Mode.FAKE);
        fleet.clear();
        failing.clear();
        systemPaused.set(false);
        crashAfter.set(-1);
        exceptionsLeft.set(0);
        entered.set(null);
        release.set(null);
        calls.clear();
        beforeEachCall.set(null);
    }

    public void useReal() {
        mode.set(Mode.REAL);
        realUsed.set(true);
    }

    /** True when a test of this JVM switched to the real U5 since the last call (the base lifts it afterwards). */
    public boolean takeRealUsed() {
        return realUsed.getAndSet(false);
    }

    public BotAdministration real() {
        return real;
    }

    /** A fleet of {@code size} ACTIVE synthetic bots, none with SYSTEM. */
    public List<UUID> fleet(int size) {
        List<UUID> created = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            UUID botId = new UUID(0x5555_0000_0000_0000L | i, UUID.randomUUID().getLeastSignificantBits());
            fleet.put(botId, false);
            created.add(botId);
        }
        return created;
    }

    /** These bots fail in their own transaction until {@link #heal()}. */
    public void failBots(Collection<UUID> botIds) {
        failing.addAll(botIds);
    }

    public void heal() {
        failing.clear();
    }

    /** The next call is interrupted after {@code changes} bots changed: the process "dies" in the middle of the batches. */
    public void crashAfter(int changes) {
        crashAfter.set(changes);
    }

    /** The next {@code count} calls throw before touching anything. */
    public void throwOnNextCalls(int count) {
        exceptionsLeft.set(count);
    }

    /**
     * The next call waits after recording the U5 copy until {@link #releaseCall()}; returns a latch counted down when
     * the call is inside U5, so a test can act while the applier holds the lock.
     */
    public CountDownLatch pauseNextCall() {
        CountDownLatch inside = new CountDownLatch(1);
        entered.set(inside);
        release.set(new CountDownLatch(1));
        return inside;
    }

    public void releaseCall() {
        CountDownLatch latch = release.get();
        if (latch != null) {
            latch.countDown();
        }
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    /** Bots of the fake fleet that have the SYSTEM reason. */
    public long withSystem() {
        return fleet.values().stream().filter(Boolean::booleanValue).count();
    }

    public int fleetSize() {
        return fleet.size();
    }

    @Override
    public SystemPauseResult applySystemPause(String correlationId) {
        calls.add(new Call("apply", correlationId));
        runHook();
        if (mode.get() == Mode.REAL) {
            return real.applySystemPause(correlationId);
        }
        return change(true);
    }

    @Override
    public SystemPauseResult liftSystemPause(String correlationId) {
        calls.add(new Call("lift", correlationId));
        runHook();
        if (mode.get() == Mode.REAL) {
            return real.liftSystemPause(correlationId);
        }
        return change(false);
    }

    @Override
    public Map<BotState, Long> countByState() {
        return real.countByState();
    }

    @Override
    public BotDashboardSummary summaryFor(Principal principal) {
        return real.summaryFor(principal);
    }

    @Override
    public boolean systemPaused() {
        return mode.get() == Mode.REAL ? real.systemPaused() : systemPaused.get();
    }

    private void runHook() {
        Runnable hook = beforeEachCall.get();
        if (hook != null) {
            hook.run();
        }
    }

    /** Like U5: the copy is written first, then each bot "in its own transaction", failures returned. */
    private SystemPauseResult change(boolean pause) {
        if (exceptionsLeft.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
            throw new IllegalStateException("simulated failure of U5");
        }
        systemPaused.set(pause);
        waitIfPaused();
        int limit = crashAfter.getAndSet(-1);
        int changed = 0;
        List<UUID> failed = new ArrayList<>();
        Map<UUID, Boolean> ordered = new LinkedHashMap<>();
        fleet.keySet().stream().sorted().forEach(id -> ordered.put(id, fleet.get(id)));
        for (Map.Entry<UUID, Boolean> bot : ordered.entrySet()) {
            if (bot.getValue() == pause) {
                continue;
            }
            if (failing.contains(bot.getKey())) {
                failed.add(bot.getKey());
                continue;
            }
            if (limit >= 0 && changed >= limit) {
                throw new IllegalStateException("simulated crash in the middle of the batches");
            }
            fleet.put(bot.getKey(), pause);
            changed++;
        }
        return new SystemPauseResult(pause, changed, failed);
    }

    private void waitIfPaused() {
        CountDownLatch inside = entered.getAndSet(null);
        // The release latch stays until the next pause or reset, so releaseCall() always finds it.
        CountDownLatch go = release.get();
        if (inside == null || go == null) {
            return;
        }
        inside.countDown();
        try {
            if (!go.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the test never released the paused call");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while paused", interrupted);
        }
    }
}
