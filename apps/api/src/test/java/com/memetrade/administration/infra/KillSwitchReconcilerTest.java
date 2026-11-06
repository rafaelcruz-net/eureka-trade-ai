package com.memetrade.administration.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.memetrade.administration.domain.InMemoryKillSwitchStore;
import com.memetrade.administration.domain.SwitchState;
import com.memetrade.bots.api.BotAdministration;
import com.memetrade.testsupport.InMemoryLockProvider;
import com.memetrade.testsupport.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Passo 5 of U8, written BEFORE {@link KillSwitchReconciler} from the rules (BR2.5, NFR9.1, NFR9.3, NFR10.1,
 * observability-design.md): the round ALWAYS applies the current state, counts a divergence between U5 and U8, and
 * raises the alerts of 60 s ({@code reconcileNeeded}) and 2 min ({@code pendingBots > 0}) by the controlled clock.
 */
@Tag("u8")
class KillSwitchReconcilerTest {

    private static final Instant START = Instant.parse("2026-11-05T12:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final InMemoryKillSwitchStore store = new InMemoryKillSwitchStore();
    private final KillSwitchApplier applier = mock(KillSwitchApplier.class);
    private final BotAdministration bots = mock(BotAdministration.class);
    private final InMemoryLockProvider locks = new InMemoryLockProvider();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final KillSwitchMetrics metrics = new KillSwitchMetrics(registry);
    private final KillSwitchReconciler reconciler = new KillSwitchReconciler(
            applier,
            store,
            bots,
            new DefaultLockingTaskExecutor(locks),
            metrics,
            new AdminProperties(AdminProperties.KillSwitch.defaults()),
            clock);

    /** BR2.5 / R-01: even with nothing pending the round applies the current state (a crash mid-batches leaves none). */
    @Test
    void everyRoundAppliesTheCurrentStateEvenWithNothingPending() {
        when(applier.applyCurrent(anyString())).thenReturn(applied(SwitchState.OFF, 0));

        KillSwitchReconciler.Round round = reconciler.runOnce();

        assertThat(round.ran()).isTrue();
        assertThat(round.diverged()).isFalse();
        assertThat(round.alerts()).isEmpty();
        verify(applier, times(1)).applyCurrent(anyString());
    }

    /** NFR9.1: ON in U8 and not paused in U5 is a divergence, counted and logged with its code. */
    @Test
    void aStateThatU5DoesNotHaveIsADivergence() {
        store.bumpVersion(SwitchState.ON);
        when(bots.systemPaused()).thenReturn(false);
        when(applier.applyCurrent(anyString())).thenReturn(applied(SwitchState.ON, 60));

        KillSwitchReconciler.Round round = reconciler.runOnce();

        assertThat(round.diverged()).isTrue();
        assertThat(round.alerts()).contains("KILL_SWITCH_DIVERGENCE");
        assertThat(metrics.divergenceCount()).isEqualTo(1.0);
    }

    /** A stable state whose bots still changed (crash in the middle of the batches) is a divergence too. */
    @Test
    void botsThatStillChangeInAStableStateAreADivergence() {
        store.bumpVersion(SwitchState.ON);
        when(bots.systemPaused()).thenReturn(true);
        when(applier.applyCurrent(anyString())).thenReturn(applied(SwitchState.ON, 30));

        KillSwitchReconciler.Round round = reconciler.runOnce();

        assertThat(round.diverged()).isTrue();
        assertThat(metrics.divergenceCount()).isEqualTo(1.0);
    }

    /** Agreement and nothing changed: no divergence. */
    @Test
    void agreementIsNotADivergence() {
        store.bumpVersion(SwitchState.ON);
        when(bots.systemPaused()).thenReturn(true);
        when(applier.applyCurrent(anyString())).thenReturn(applied(SwitchState.ON, 0));

        assertThat(reconciler.runOnce().diverged()).isFalse();
        assertThat(metrics.divergenceCount()).isZero();
    }

    /** NFR9.3: reconcileNeeded for more than 60 s raises KILL_SWITCH_RECONCILE_STALE; at 60 s it does not yet. */
    @Test
    void reconcileNeededForMoreThanSixtySecondsIsAnAlert() {
        store.force(0, true);
        when(applier.applyCurrent(anyString())).thenReturn(stillNeeded());

        assertThat(reconciler.runOnce().alerts()).doesNotContain("KILL_SWITCH_RECONCILE_STALE");
        clock.advance(Duration.ofSeconds(60));
        assertThat(reconciler.runOnce().alerts()).doesNotContain("KILL_SWITCH_RECONCILE_STALE");
        clock.advance(Duration.ofSeconds(1));
        KillSwitchReconciler.Round late = reconciler.runOnce();

        assertThat(late.alerts()).contains("KILL_SWITCH_RECONCILE_STALE");
        assertThat(reconciler.activeAlerts()).anySatisfy(alert -> {
            assertThat(alert.code()).isEqualTo("KILL_SWITCH_RECONCILE_STALE");
            assertThat(alert.lasting()).isEqualTo(Duration.ofSeconds(61));
        });
    }

    /** NFR9.3: pendingBots > 0 for more than 2 minutes raises KILL_SWITCH_PENDING_STALE. */
    @Test
    void pendingBotsForMoreThanTwoMinutesIsAnAlert() {
        store.force(3, false);
        when(applier.applyCurrent(anyString())).thenReturn(pending(3));

        reconciler.runOnce();
        clock.advance(Duration.ofSeconds(119));
        assertThat(reconciler.runOnce().alerts()).doesNotContain("KILL_SWITCH_PENDING_STALE");
        clock.advance(Duration.ofSeconds(2));

        assertThat(reconciler.runOnce().alerts()).contains("KILL_SWITCH_PENDING_STALE");
    }

    /** The alert ends as soon as the condition ends. */
    @Test
    void theAlertEndsWhenTheConditionEnds() {
        store.force(2, false);
        when(applier.applyCurrent(anyString())).thenReturn(pending(2));
        reconciler.runOnce();
        clock.advance(Duration.ofMinutes(3));
        assertThat(reconciler.runOnce().alerts()).contains("KILL_SWITCH_PENDING_STALE");

        store.force(0, false);
        when(applier.applyCurrent(anyString())).thenReturn(applied(SwitchState.OFF, 2));
        clock.advance(Duration.ofSeconds(30));

        assertThat(reconciler.runOnce().alerts()).doesNotContain("KILL_SWITCH_PENDING_STALE");
        assertThat(reconciler.activeAlerts()).isEmpty();
    }

    /** NFR2.7: another instance holds the round; nothing is applied and nothing is counted. */
    @Test
    void aRoundHeldByAnotherInstanceDoesNothing() {
        locks.hold(KillSwitchReconciler.LOCK_NAME);

        KillSwitchReconciler.Round round = reconciler.runOnce();

        assertThat(round.ran()).isFalse();
        verify(applier, never()).applyCurrent(anyString());
    }

    /** A failure of the applier never escapes the scheduled round; the next round tries again. */
    @Test
    void aFailureOfTheRoundIsContainedAndRetriedNextTime() {
        when(applier.applyCurrent(anyString()))
                .thenThrow(new IllegalStateException("database down"))
                .thenReturn(applied(SwitchState.OFF, 0));

        KillSwitchReconciler.Round failed = reconciler.runOnce();
        KillSwitchReconciler.Round next = reconciler.runOnce();

        assertThat(failed.ran()).isTrue();
        assertThat(failed.outcome()).isEmpty();
        assertThat(next.outcome()).isPresent();
    }

    private static KillSwitchApplier.Outcome applied(SwitchState state, int changed) {
        return new KillSwitchApplier.Outcome(KillSwitchApplier.Result.APPLIED, state, 0, false, changed, 1);
    }

    private static KillSwitchApplier.Outcome stillNeeded() {
        return new KillSwitchApplier.Outcome(KillSwitchApplier.Result.FAILED, SwitchState.ON, 0, true, 0, 1);
    }

    private static KillSwitchApplier.Outcome pending(int bots) {
        return new KillSwitchApplier.Outcome(KillSwitchApplier.Result.PARTIAL, SwitchState.ON, bots, false, 0, 3);
    }
}
