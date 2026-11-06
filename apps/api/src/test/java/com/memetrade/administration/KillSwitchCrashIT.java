package com.memetrade.administration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.administration.domain.KillSwitchService;
import com.memetrade.administration.domain.KillSwitchStore;
import com.memetrade.administration.domain.SwitchState;
import com.memetrade.administration.infra.AdminProperties;
import com.memetrade.administration.infra.ApplyLock;
import com.memetrade.administration.infra.KillSwitchApplier;
import com.memetrade.administration.infra.KillSwitchReconciler;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.Principal;
import com.memetrade.testsupport.AbstractU8IT;
import java.time.Duration;
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
 * Passo 5 of U8, written BEFORE the applier and the reconciliation from the rules (BR2.5, NFR2.2, NFR10.1,
 * reliability-design.md "Testes de queda", review R-01 and R-10 of the NFR Design): three crashes, and in each one the
 * next reconciliation round leaves the 60 bots with SYSTEM. The round runs every 30 s, so with the 5 s of the answer
 * and the 10 s of the lock the worst case is 45 s.
 */
class KillSwitchCrashIT extends AbstractU8IT {

    private static final int BOTS = 60;

    private final ExecutorService background = Executors.newSingleThreadExecutor();

    @Autowired
    private KillSwitchApplier applier;

    @Autowired
    private KillSwitchReconciler reconciler;

    @Autowired
    private KillSwitchStore store;

    @Autowired
    private KillSwitchService service;

    @Autowired
    private AdminProperties properties;

    @AfterEach
    void stopTheBackground() {
        u5.releaseCall();
        background.shutdownNow();
    }

    /** Crash 1: the process dies after committing ON and before calling U5; nothing was marked. */
    @Test
    void aCrashBeforeTheCallToU5IsRepairedByTheNextRound() {
        u5.fleet(BOTS);
        switchOn(newAdministrator());
        assertThat(u5.withSystem()).isZero();
        assertThat(store.read().reconcileNeeded()).isFalse();

        KillSwitchReconciler.Round round = reconciler.runOnce();

        assertThat(round.diverged()).isTrue();
        assertThat(u5.withSystem()).isEqualTo(BOTS);
        assertThat(u5.systemPaused()).isTrue();
        assertThat(store.read().reconcileNeeded()).isFalse();
        assertThat(store.read().pendingBots()).isZero();
    }

    /**
     * Crash 2 (R-01): the process dies in the middle of the batches of U5, after 30 of the 60 bots; U5 already wrote its
     * copy and U8 wrote nothing. The round still applies because it ALWAYS applies the current state.
     */
    @Test
    void aCrashInTheMiddleOfTheBatchesIsRepairedByTheNextRound() {
        u5.fleet(BOTS);
        switchOn(newAdministrator());
        u5.crashAfter(30);
        assertThatThrownBy(() -> u5.applySystemPause("process-dies")).isInstanceOf(IllegalStateException.class);
        assertThat(u5.withSystem()).isEqualTo(30);
        assertThat(u5.systemPaused()).isTrue();
        assertThat(store.read().reconcileNeeded()).isFalse();

        KillSwitchReconciler.Round round = reconciler.runOnce();

        assertThat(round.diverged()).isTrue();
        assertThat(u5.withSystem()).isEqualTo(BOTS);
        assertThat(store.read().reconcileNeeded()).isFalse();
    }

    /** The same interruption seen by a living applier: an exception of U5 marks the reconciliation. */
    @Test
    void anExceptionOfU5MarksTheReconciliationAndTheRoundFinishesTheJob() {
        u5.fleet(BOTS);
        switchOn(newAdministrator());
        u5.crashAfter(30);

        KillSwitchApplier.Outcome outcome = applier.applyCurrent("u5-throws");

        assertThat(outcome.result()).isEqualTo(KillSwitchApplier.Result.FAILED);
        assertThat(outcome.reconcileNeeded()).isTrue();
        assertThat(store.read().reconcileNeeded()).isTrue();
        assertThat(u5.withSystem()).isEqualTo(30);

        reconciler.runOnce();

        assertThat(u5.withSystem()).isEqualTo(BOTS);
        assertThat(store.read().reconcileNeeded()).isFalse();
    }

    /**
     * Crash 3: the connection of the lock dies during the call; PostgreSQL releases the advisory lock, so the applier
     * cannot trust its result: it marks the reconciliation and never writes pendingBots. The next round repairs.
     */
    @Test
    void aLostLockConnectionDuringTheCallIsRepairedByTheNextRound() throws Exception {
        java.util.List<UUID> fleet = u5.fleet(BOTS);
        switchOn(newAdministrator());
        u5.failBots(fleet.subList(0, 2));
        CountDownLatch inside = u5.pauseNextCall();

        Future<KillSwitchApplier.Outcome> running = background.submit(() -> applier.applyCurrent("lock-lost"));
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
        int terminated = terminateTheLockSession();
        u5.releaseCall();
        KillSwitchApplier.Outcome outcome = running.get(20, TimeUnit.SECONDS);

        assertThat(terminated).isEqualTo(1);
        assertThat(outcome.result()).isEqualTo(KillSwitchApplier.Result.LOCK_LOST);
        assertThat(outcome.reconcileNeeded()).isTrue();
        assertThat(store.read().reconcileNeeded()).isTrue();
        // Without the lock the result of the call is not trusted: the two failing bots are NOT written as pending.
        assertThat(store.read().pendingBots()).isZero();

        u5.heal();
        reconciler.runOnce();

        assertThat(u5.withSystem()).isEqualTo(BOTS);
        assertThat(store.read().reconcileNeeded()).isFalse();
    }

    /** NFR2.2 (45 s in the worst case, Q2 of the NFR Design): 30 s between rounds plus 10 s of lock plus 5 s of answer. */
    @Test
    void theWorstCaseFitsFortyFiveSeconds() {
        AdminProperties.KillSwitch settings = properties.killSwitch();
        Duration worst =
                settings.reconcileInterval().plus(settings.applyLockWait()).plus(Duration.ofSeconds(5));

        assertThat(settings.reconcileInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(worst).isLessThanOrEqualTo(Duration.ofSeconds(45));
    }

    private void switchOn(Principal admin) {
        service.change(new KillSwitchService.Command(
                SwitchState.ON, "teste de queda", admin, new IdempotencyKey(UUID.randomUUID()), "crash-on"));
    }

    /** The superuser ends the session that holds the advisory lock of U8 (a dead process or a cut network). */
    private int terminateTheLockSession() {
        return connect(SUPERUSER)
                .sql("""
                        select count(*) from (
                            select pg_terminate_backend(pid) from pg_locks
                            where locktype = 'advisory' and granted
                              and classid = cast((:key >> 32) as oid) and objid = cast((:key & 4294967295) as oid)
                              and objsubid = 1) terminated
                        """)
                .param("key", ApplyLock.KEY)
                .query(Integer.class)
                .single();
    }
}
