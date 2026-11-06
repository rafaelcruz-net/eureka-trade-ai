package com.memetrade.administration.infra;

import com.memetrade.administration.domain.KillSwitchStore;
import com.memetrade.administration.domain.KillSwitchStore.Fence;
import com.memetrade.administration.domain.SwitchState;
import com.memetrade.bots.api.BotAdministration;
import com.memetrade.bots.api.BotAdministration.SystemPauseResult;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ONLY caller of {@link BotAdministration#applySystemPause} and {@link BotAdministration#liftSystemPause} (BR2.3,
 * BR2.8, reliability-design.md; ArchUnit rule). Every call happens under the {@link ApplyLock}, and always applies the
 * CURRENT state, read under the lock, never the state the caller wrote:
 *
 * <ol>
 *   <li>without the lock within the budget: mark {@code reconcileNeeded} (unconditional) and return; U5 is not called;
 *   <li>read the fence (state, version, start by the database clock) and call U5 for that state; while bots fail, call
 *       again, up to {@code attempts} calls (BR2.3); an exception of U5 marks {@code reconcileNeeded} and is logged with
 *       {@code KILL_SWITCH_APPLY_FAILED};
 *   <li>check the session of the lock; when it died, the lock may have been taken by someone else during the call: mark
 *       and never write {@code pendingBots};
 *   <li>write {@code pendingBots} and clear the flag only through the fence (same version, no mark after the start);
 *       when the version changed, read again and apply the new state, up to {@code attempts} times, then mark; when
 *       only a mark was made during the call, it stays (the reconciliation applies again).
 * </ol>
 *
 * Logs carry only state, version, counts and the outcome, never the reason or a bot of a person.
 */
public class KillSwitchApplier {

    private static final Logger log = LoggerFactory.getLogger(KillSwitchApplier.class);

    private final ApplyLock lock;
    private final KillSwitchStore store;
    private final BotAdministration bots;
    private final KillSwitchMetrics metrics;
    private final int attempts;

    public KillSwitchApplier(
            ApplyLock lock, KillSwitchStore store, BotAdministration bots, KillSwitchMetrics metrics, int attempts) {
        this.lock = Objects.requireNonNull(lock, "lock");
        this.store = Objects.requireNonNull(store, "store");
        this.bots = Objects.requireNonNull(bots, "bots");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be positive");
        }
        this.attempts = attempts;
    }

    public Outcome applyCurrent(String correlationId) {
        Optional<ApplyLock.Held> acquired = lock.tryAcquire();
        if (acquired.isEmpty()) {
            store.markReconcile();
            return finish(Result.LOCK_TIMEOUT, store.read().state(), 0, 0);
        }
        try (ApplyLock.Held held = acquired.get()) {
            return underLock(held, correlationId);
        }
    }

    private Outcome underLock(ApplyLock.Held held, String correlationId) {
        int calls = 0;
        for (int round = 1; round <= attempts; round++) {
            Fence fence = store.fence();
            Call call;
            try {
                call = callWithRetries(fence.state(), correlationId);
            } catch (RuntimeException failure) {
                log.error(
                        "KILL_SWITCH_APPLY_FAILED: state={}, version={}, cause={}",
                        fence.state(),
                        fence.version(),
                        failure.getClass().getSimpleName());
                store.markReconcile();
                return finish(Result.FAILED, fence.state(), 0, calls + 1);
            }
            calls += call.calls();
            if (!held.stillValid()) {
                log.error(
                        "Kill switch apply lock lost during the call: state={}, version={}",
                        fence.state(),
                        fence.version());
                store.markReconcile();
                return finish(Result.LOCK_LOST, fence.state(), call.result().changed(), calls);
            }
            int pending = call.result().failed().size();
            if (store.recordOutcome(fence, pending)) {
                return finish(
                        pending == 0 ? Result.APPLIED : Result.PARTIAL,
                        fence.state(),
                        call.result().changed(),
                        calls);
            }
            if (store.read().version() == fence.version()) {
                // A reconciliation was asked for during the call: the mark stays and the next round applies again.
                return finish(
                        Result.MARKED_DURING_APPLY, fence.state(), call.result().changed(), calls);
            }
            log.info("Kill switch version changed during the call: version={}, round={}", fence.version(), round);
        }
        store.markReconcile();
        return finish(Result.VERSION_CHANGING, store.read().state(), 0, calls);
    }

    /** BR2.3: up to {@code attempts} calls of U5 while some bot still fails. */
    private Call callWithRetries(SwitchState state, String correlationId) {
        SystemPauseResult result = null;
        int calls = 0;
        while (calls < attempts && (result == null || !result.failed().isEmpty())) {
            calls++;
            long started = System.nanoTime();
            try {
                result = state.paused() ? bots.applySystemPause(correlationId) : bots.liftSystemPause(correlationId);
            } catch (RuntimeException failure) {
                metrics.call(state.paused(), KillSwitchMetrics.Outcome.FAILED, since(started));
                throw failure;
            }
            metrics.call(
                    state.paused(),
                    result.failed().isEmpty() ? KillSwitchMetrics.Outcome.OK : KillSwitchMetrics.Outcome.PARTIAL,
                    since(started));
        }
        return new Call(result, calls);
    }

    private Outcome finish(Result result, SwitchState state, int changed, int calls) {
        var after = store.read();
        metrics.observed(after);
        log.info(
                "Kill switch applied: outcome={}, state={}, version={}, pendingBots={}, changed={}, calls={}",
                result,
                state,
                after.version(),
                after.pendingBots(),
                changed,
                calls);
        return new Outcome(result, state, after.pendingBots(), after.reconcileNeeded(), changed, calls);
    }

    private static Duration since(long started) {
        return Duration.ofNanos(System.nanoTime() - started);
    }

    private record Call(SystemPauseResult result, int calls) {}

    /** How one application ended. */
    public enum Result {
        /** U5 has the current state and no bot failed. */
        APPLIED,
        /** U5 has the current state but some bots still failed: they are {@code pendingBots}. */
        PARTIAL,
        /** The lock could not be taken within the budget; {@code reconcileNeeded} is marked. */
        LOCK_TIMEOUT,
        /** U5 threw; {@code reconcileNeeded} is marked. */
        FAILED,
        /** The session of the lock died during the call; {@code reconcileNeeded} is marked. */
        LOCK_LOST,
        /** The version kept changing for every attempt; {@code reconcileNeeded} is marked. */
        VERSION_CHANGING,
        /** A reconciliation was asked for during the call; its mark stays. */
        MARKED_DURING_APPLY
    }

    /**
     * @param state the state that was applied (or that was current when the lock could not be taken)
     * @param pendingBots bots still failing, as recorded
     * @param reconcileNeeded true when the reconciliation must apply again
     * @param changed bots changed by U5 in the last call
     * @param calls calls made to U5
     */
    public record Outcome(
            Result result, SwitchState state, int pendingBots, boolean reconcileNeeded, int changed, int calls) {}
}
