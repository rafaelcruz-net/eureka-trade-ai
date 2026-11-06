package com.memetrade.administration;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.administration.domain.KillSwitchService;
import com.memetrade.administration.domain.KillSwitchSnapshot;
import com.memetrade.administration.domain.KillSwitchStore;
import com.memetrade.administration.domain.SwitchState;
import com.memetrade.administration.infra.KillSwitchApplier;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.Principal;
import com.memetrade.testsupport.AbstractU8IT;
import com.memetrade.testsupport.ControllableBotAdministration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Passo 5 of U8, written BEFORE the applier and the store from the rules (BR2.3, BR2.8, reliability-design.md items 3 to
 * 5, review R-09 of the NFR Design): the fencing of {@code reconcile_marked_at} never erases a mark made during an
 * application; a newer version during the call is applied instead of the old state; a version that keeps changing ends
 * with {@code reconcileNeeded} after 3 attempts; only the holder writes {@code pendingBots}.
 */
class KillSwitchFencingIT extends AbstractU8IT {

    private final ExecutorService background = Executors.newSingleThreadExecutor();

    @Autowired
    private KillSwitchApplier applier;

    @Autowired
    private KillSwitchStore store;

    @Autowired
    private KillSwitchService service;

    @AfterEach
    void stopTheBackground() {
        u5.releaseCall();
        background.shutdownNow();
    }

    @Test
    void aMarkMadeDuringTheApplicationIsNeverErased() throws Exception {
        Principal admin = newAdministrator();
        u5.fleet(10);
        switchTo(admin, SwitchState.ON);
        CountDownLatch inside = u5.pauseNextCall();

        Future<KillSwitchApplier.Outcome> running = background.submit(() -> applier.applyCurrent("fencing-mark"));
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
        store.markReconcile();
        u5.releaseCall();
        KillSwitchApplier.Outcome outcome = running.get(15, TimeUnit.SECONDS);

        assertThat(outcome.result()).isEqualTo(KillSwitchApplier.Result.MARKED_DURING_APPLY);
        assertThat(outcome.reconcileNeeded()).isTrue();
        assertThat(store.read().reconcileNeeded()).isTrue();
        assertThat(u5.calls()).hasSize(1);
    }

    @Test
    void aMarkMadeBeforeTheApplicationIsClearedByIt() {
        u5.fleet(5);
        store.markReconcile();

        KillSwitchApplier.Outcome outcome = applier.applyCurrent("fencing-old-mark");

        assertThat(outcome.result()).isEqualTo(KillSwitchApplier.Result.APPLIED);
        assertThat(store.read().reconcileNeeded()).isFalse();
    }

    /** BR2.8: an apply that would arrive late after an OFF becomes a lift. */
    @Test
    void aNewerVersionDuringTheCallIsAppliedInsteadOfTheOldState() throws Exception {
        Principal admin = newAdministrator();
        u5.fleet(10);
        switchTo(admin, SwitchState.ON);
        CountDownLatch inside = u5.pauseNextCall();

        Future<KillSwitchApplier.Outcome> running = background.submit(() -> applier.applyCurrent("fencing-version"));
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
        switchTo(admin, SwitchState.OFF);
        u5.releaseCall();
        KillSwitchApplier.Outcome outcome = running.get(15, TimeUnit.SECONDS);

        assertThat(outcome.state()).isEqualTo(SwitchState.OFF);
        assertThat(outcome.reconcileNeeded()).isFalse();
        assertThat(u5.calls())
                .extracting(ControllableBotAdministration.Call::operation)
                .containsExactly("apply", "lift");
        assertThat(u5.systemPaused()).isFalse();
        assertThat(u5.withSystem()).isZero();
        assertThat(store.read().reconcileNeeded()).isFalse();
    }

    /** reliability-design.md item 5: 3 repetitions with the version still changing end with reconcileNeeded. */
    @Test
    void aVersionThatKeepsChangingEndsWithReconcileNeededAfterThreeAttempts() {
        u5.fleet(3);
        u5.beforeEachCall(() -> jdbc.sql("update kill_switch_state set version = version + 1 where id = 1")
                .update());

        KillSwitchApplier.Outcome outcome = applier.applyCurrent("fencing-moving");

        assertThat(outcome.result()).isEqualTo(KillSwitchApplier.Result.VERSION_CHANGING);
        assertThat(outcome.reconcileNeeded()).isTrue();
        assertThat(u5.calls()).hasSize(3);
        assertThat(store.read().reconcileNeeded()).isTrue();
    }

    /** BR2.3, NFR10.3: up to 3 calls in the same application; what still fails becomes pendingBots. */
    @Test
    void failuresThatPersistAfterThreeCallsBecomePendingBots() {
        Principal admin = newAdministrator();
        List<UUID> fleet = u5.fleet(6);
        u5.failBots(fleet.subList(0, 2));
        switchTo(admin, SwitchState.ON);

        KillSwitchApplier.Outcome outcome = applier.applyCurrent("fencing-partial");

        assertThat(outcome.result()).isEqualTo(KillSwitchApplier.Result.PARTIAL);
        assertThat(outcome.pendingBots()).isEqualTo(2);
        assertThat(u5.calls()).hasSize(3);
        KillSwitchSnapshot after = store.read();
        assertThat(after.pendingBots()).isEqualTo(2);
        assertThat(after.reconcileNeeded()).isFalse();
        assertThat(u5.withSystem()).isEqualTo(4);

        u5.heal();
        KillSwitchApplier.Outcome healed = applier.applyCurrent("fencing-healed");

        assertThat(healed.pendingBots()).isZero();
        assertThat(store.read().pendingBots()).isZero();
        assertThat(u5.withSystem()).isEqualTo(6);
    }

    /** A failure that goes away on the second call costs two calls, never a pending bot. */
    @Test
    void aFailureThatGoesAwayIsRepeatedInTheSameApplication() {
        Principal admin = newAdministrator();
        List<UUID> fleet = u5.fleet(4);
        u5.failBots(fleet.subList(0, 1));
        switchTo(admin, SwitchState.ON);
        u5.beforeEachCall(() -> {
            if (u5.calls().size() == 2) {
                u5.heal();
            }
        });

        KillSwitchApplier.Outcome outcome = applier.applyCurrent("fencing-transient");

        assertThat(outcome.result()).isEqualTo(KillSwitchApplier.Result.APPLIED);
        assertThat(outcome.pendingBots()).isZero();
        assertThat(u5.calls()).hasSize(2);
    }

    private void switchTo(Principal admin, SwitchState state) {
        service.change(new KillSwitchService.Command(
                state, "teste de fencing", admin, new IdempotencyKey(UUID.randomUUID()), "fencing-" + state));
    }
}
