package com.memetrade.administration.infra;

import com.memetrade.administration.domain.KillSwitchSnapshot;
import com.memetrade.administration.domain.KillSwitchStore;
import com.memetrade.bots.api.BotAdministration;
import com.memetrade.shared.config.ProcessRole;
import com.memetrade.shared.web.CorrelationId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The reconciliation of the kill switch (BR2.5, NFR10.1, reliability-design.md "Reconciliação"): every 30 s and once
 * when the api is ready ({@link KillSwitchStartup}), once across instances (ShedLock {@value #LOCK_NAME}), only in the
 * API process. Each round ALWAYS applies the current state through the {@link KillSwitchApplier}: the operations of U5
 * are idempotent and only touch the bots that still differ, so a crash before the call, in the middle of the batches or
 * with the lock lost is repaired by the next round, even with {@code pendingBots = 0} and no mark.
 *
 * <p>Alerts of NFR9.3, by the injectable clock, logged at ERROR with a stable code on every round while they last and
 * exposed to the health indicator {@code adminKillSwitch}:
 *
 * <ul>
 *   <li>{@code KILL_SWITCH_RECONCILE_STALE}: {@code reconcileNeeded} for more than 60 s;
 *   <li>{@code KILL_SWITCH_PENDING_STALE}: {@code pendingBots > 0} for more than 2 minutes;
 *   <li>{@code KILL_SWITCH_DIVERGENCE}: U5 disagreed with U8 at the start of the round, or bots still changed while the
 *       state was stable (the round also increments the divergence counter).
 * </ul>
 */
@Component
@ProcessRole(ProcessRole.Kind.API)
public class KillSwitchReconciler {

    public static final String LOCK_NAME = "adminKillSwitchReconcile";

    static final String RECONCILE_STALE = "KILL_SWITCH_RECONCILE_STALE";
    static final String PENDING_STALE = "KILL_SWITCH_PENDING_STALE";
    static final String DIVERGENCE = "KILL_SWITCH_DIVERGENCE";

    private static final Logger log = LoggerFactory.getLogger(KillSwitchReconciler.class);

    private final KillSwitchApplier applier;
    private final KillSwitchStore store;
    private final BotAdministration bots;
    private final LockingTaskExecutor locks;
    private final KillSwitchMetrics metrics;
    private final AdminProperties.KillSwitch settings;
    private final Clock clock;
    private final AtomicReference<Instant> reconcileSince = new AtomicReference<>();
    private final AtomicReference<Instant> pendingSince = new AtomicReference<>();
    private final AtomicReference<Boolean> lastDiverged = new AtomicReference<>(false);

    public KillSwitchReconciler(
            KillSwitchApplier applier,
            KillSwitchStore store,
            BotAdministration bots,
            LockingTaskExecutor locks,
            KillSwitchMetrics metrics,
            AdminProperties properties,
            Clock clock) {
        this.applier = applier;
        this.store = store;
        this.bots = bots;
        this.locks = locks;
        this.metrics = metrics;
        this.settings = properties.killSwitch();
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${memetrade.admin.kill-switch.reconcile-interval}",
            initialDelayString = "${memetrade.admin.kill-switch.reconcile-interval}")
    public void scheduled() {
        runOnce();
    }

    /** One round now, through the scheduler lock (the scheduled entry point, the start-up and the tests). */
    public Round runOnce() {
        return CorrelationId.withNew(() -> {
            LockConfiguration lock =
                    new LockConfiguration(clock.instant(), LOCK_NAME, settings.lockAtMostFor(), Duration.ZERO);
            try {
                LockingTaskExecutor.TaskResult<Round> result = locks.executeWithLock(this::round, lock);
                if (!result.wasExecuted()) {
                    log.info("Kill switch reconciliation skipped: another instance holds the round");
                    return new Round(false, false, Optional.empty(), List.of());
                }
                return result.getResult();
            } catch (RuntimeException | Error unexpected) {
                throw unexpected;
            } catch (Throwable checked) {
                throw new IllegalStateException("The kill switch reconciliation failed", checked);
            }
        });
    }

    private Round round() {
        try {
            KillSwitchSnapshot before = store.read();
            boolean u5Paused = bots.systemPaused();
            KillSwitchApplier.Outcome outcome = applier.applyCurrent(CorrelationId.current());
            KillSwitchSnapshot after = store.read();
            boolean stable = before.version() == after.version();
            boolean diverged = u5Paused != before.state().paused() || (stable && outcome.changed() > 0);
            lastDiverged.set(diverged);
            if (diverged) {
                metrics.divergence();
                log.error(
                        "{}: state={}, version={}, u5SystemPaused={}, changed={}",
                        DIVERGENCE,
                        before.state(),
                        before.version(),
                        u5Paused,
                        outcome.changed());
            }
            List<String> alerts = track(after, diverged);
            return new Round(true, diverged, Optional.of(outcome), alerts);
        } catch (RuntimeException failure) {
            // Contained: the next round tries again (BR2.5 "falha técnica registrada e repetida").
            log.error(
                    "Kill switch reconciliation round failed: cause={}",
                    failure.getClass().getSimpleName(),
                    failure);
            return new Round(true, false, Optional.empty(), List.of());
        }
    }

    private List<String> track(KillSwitchSnapshot after, boolean diverged) {
        Instant now = clock.instant();
        reconcileSince.updateAndGet(since -> after.reconcileNeeded() ? (since == null ? now : since) : null);
        pendingSince.updateAndGet(since -> after.pendingBots() > 0 ? (since == null ? now : since) : null);
        List<String> alerts = new ArrayList<>();
        for (Alert alert : activeAlerts(now)) {
            alerts.add(alert.code());
            log.error(
                    "{}: lastingSeconds={}, state={}, pendingBots={}",
                    alert.code(),
                    alert.lasting().toSeconds(),
                    after.state(),
                    after.pendingBots());
        }
        if (diverged) {
            alerts.add(DIVERGENCE);
        }
        return List.copyOf(alerts);
    }

    /** The stale alerts that last NOW (read by the health indicator between rounds). */
    public List<Alert> activeAlerts() {
        return activeAlerts(clock.instant());
    }

    private List<Alert> activeAlerts(Instant now) {
        List<Alert> alerts = new ArrayList<>();
        stale(reconcileSince.get(), settings.reconcileStaleAfter(), now)
                .ifPresent(lasting -> alerts.add(new Alert(RECONCILE_STALE, lasting)));
        stale(pendingSince.get(), settings.pendingStaleAfter(), now)
                .ifPresent(lasting -> alerts.add(new Alert(PENDING_STALE, lasting)));
        return List.copyOf(alerts);
    }

    /** Whether the last round found a divergence (informational detail of the health indicator). */
    public boolean lastRoundDiverged() {
        return Boolean.TRUE.equals(lastDiverged.get());
    }

    private static Optional<Duration> stale(Instant since, Duration limit, Instant now) {
        if (since == null) {
            return Optional.empty();
        }
        Duration lasting = Duration.between(since, now);
        return lasting.compareTo(limit) > 0 ? Optional.of(lasting) : Optional.empty();
    }

    /** One round: whether it ran, whether U5 and U8 disagreed, what the applier did, the alerts after the round. */
    public record Round(
            boolean ran, boolean diverged, Optional<KillSwitchApplier.Outcome> outcome, List<String> alerts) {}

    /** An alert of NFR9.3 that is active now, with how long its condition has lasted. */
    public record Alert(String code, Duration lasting) {}
}
