package com.memetrade.botwallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.memetrade.botwallet.api.RealAdminRefused;
import com.memetrade.botwallet.api.RealExecutionWallet;
import com.memetrade.botwallet.api.RealKillSwitchState;
import com.memetrade.botwallet.api.RealOperationMode;
import com.memetrade.botwallet.api.RealPauseReason;
import com.memetrade.botwallet.api.RealStateQuery;
import com.memetrade.botwallet.api.RealStateView;
import com.memetrade.botwallet.application.RealKillSwitchService;
import com.memetrade.testsupport.Concurrently;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Step 5 of the U5, written BEFORE the code (F6, BR3.1, BR3.2, S16, S17, S26, U5-NFR3.5, U5-NFR4.1): the kill switch REAL
 * of the M7. The live read pauses every wallet at once, even one created after it was switched on; the job cancels the
 * unsent orders of everybody while it is on; the sent ones go on and the withdrawal stays free; switching off restarts
 * nothing; switching on and off are serialised by the version, and a repetition has no effect.
 */
@Tag("u5-real-operations")
class KillSwitchIT extends AbstractRealOpsIT {

    @Autowired
    private RealKillSwitchService killSwitch;

    @Autowired
    private RealStateQuery state;

    @Autowired
    private RealExecutionWallet wallets;

    private RealKillSwitchState.KillSwitchChange activate(UUID admin) {
        return transactions.execute(status -> killSwitch.activate(admin, "incidente no RPC"));
    }

    private RealKillSwitchState.KillSwitchChange deactivate(UUID admin) {
        return transactions.execute(status -> killSwitch.deactivate(admin, "RPC normalizado"));
    }

    /** S16: on, the unsent orders of everybody are cancelled; the sent ones go on; the withdrawal stays free. */
    @Test
    void switchingOnCancelsTheUnsentOrdersOfEverybodyAndLeavesTheSentOnesAndTheWithdrawal() throws Exception {
        OpsPerson first = activePerson();
        OpsPerson second = activePerson();
        UUID quoted = order(first, "QUOTED");
        UUID proposed = order(second, "PROPOSED");
        UUID sent = order(second, "SENT");
        deposit(first, 2_000_000_000L);
        UUID admin = UUID.randomUUID();

        RealKillSwitchState.KillSwitchChange on = activate(admin);
        int cancelled = killSwitch.cancelUnsentWhileActive();

        assertThat(on.changed()).isTrue();
        assertThat(on.view().active()).isTrue();
        assertThat(on.view().activatedBy()).isEqualTo(admin);
        assertThat(cancelled).isGreaterThanOrEqualTo(2);
        assertThat(orderState(quoted)).isEqualTo("CANCELLED:KILL_SWITCH");
        assertThat(orderState(proposed)).isEqualTo("CANCELLED:KILL_SWITCH");
        assertThat(orderState(sent)).isEqualTo("SENT");
        assertThat(call(
                                first.person(),
                                post("/api/v1/real/bot-wallet/withdrawals")
                                        .content("{\"amount\":{\"lamports\":\"100000000\"}}"))
                        .getStatus())
                .isEqualTo(202);
        assertThat(jdbc.sql("select count(*) from audit_outbox where payload ->> 'action' ="
                                + " 'REAL_KILL_SWITCH_ACTIVATED' and payload ->> 'actor' = :admin")
                        .param("admin", admin.toString())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    /** S26, U5-NFR3.5: a wallet created after the switch is paused at once by the live read, before signing and sending. */
    @Test
    void aWalletCreatedAfterTheSwitchIsPausedAtOnceByTheLiveRead() throws Exception {
        activate(UUID.randomUUID());
        Instant activatedAt = killSwitch.current().activatedAt();
        clock.advance(Duration.ofMinutes(2));
        OpsPerson later = activePerson();
        releaseOf(later.userId(), "LOCALNET", "ACTIVE", Duration.ofHours(30));

        RealStateView view =
                state.readRealState(later.tenantId(), later.userId()).orElseThrow();

        assertThat(view.pausedReasons()).containsExactly(RealPauseReason.KILL_SWITCH);
        assertThat(view.pausedSince()).containsEntry(RealPauseReason.KILL_SWITCH, activatedAt);
        assertThat(wallets.recheckBeforeSend(later.tenantId(), later.userId(), RealOperationMode.AUTO_FULL, false))
                .isEqualTo(new RealExecutionWallet.Recheck.Cancelled(RealExecutionWallet.RecheckReason.KILL_SWITCH));
    }

    /** S17: off, the pause leaves; the pauses of the person and the stopped bots stay as they are. */
    @Test
    void switchingOffRemovesThePauseAndRestartsNothing() throws Exception {
        OpsPerson person = activePerson();
        jdbc.sql("update real_preferences set paused_reasons = array['USER'] where bot_wallet_id = :w")
                .param("w", person.botWalletId())
                .update();
        UUID bot = stoppedBot(person);
        activate(UUID.randomUUID());

        RealKillSwitchState.KillSwitchChange off = deactivate(UUID.randomUUID());

        assertThat(off.changed()).isTrue();
        assertThat(off.view().active()).isFalse();
        assertThat(state.readRealState(person.tenantId(), person.userId())
                        .orElseThrow()
                        .pausedReasons())
                .containsExactly(RealPauseReason.USER);
        assertThat(jdbc.sql("select state from bot where bot_id = :b")
                        .param("b", bot)
                        .query(String.class)
                        .single())
                .isEqualTo("STOPPED");
        assertThat(killSwitch.cancelUnsentWhileActive()).isZero();
    }

    /** BR3.1: the person sees the global pause in the view of the bot wallet too, while the switch is on. */
    @Test
    void theViewOfThePersonShowsTheKillSwitchWhileItIsOn() throws Exception {
        OpsPerson person = activePerson();
        activate(UUID.randomUUID());

        String on = body(call(
                        person.person(),
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/api/v1/real/bot-wallet")))
                .get("pausedReason")
                .asText();
        deactivate(UUID.randomUUID());
        String off = body(call(
                        person.person(),
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/api/v1/real/bot-wallet")))
                .get("pausedReason")
                .asText();

        assertThat(on).isEqualTo("KILL_SWITCH");
        assertThat(off).isEqualTo("NONE");
    }

    @Test
    void switchingOnTwiceOrOffWhenOffHasNoEffect() {
        RealKillSwitchState.KillSwitchChange first = activate(UUID.randomUUID());
        RealKillSwitchState.KillSwitchChange again = activate(UUID.randomUUID());

        assertThat(again.changed()).isFalse();
        assertThat(again.view().version()).isEqualTo(first.view().version());
        assertThat(again.view().activatedBy()).isEqualTo(first.view().activatedBy());

        deactivate(UUID.randomUUID());
        assertThat(deactivate(UUID.randomUUID()).changed()).isFalse();
    }

    /** U5-NFR4.1: two switches on at the same time: one change, one version step. */
    @Test
    void twoRequestsAtTheSameTimeChangeTheSwitchOnce() {
        long before = killSwitch.current().version();

        List<Concurrently.Outcome<Boolean>> outcomes =
                Concurrently.run(() -> activate(UUID.randomUUID()).changed(), () -> activate(UUID.randomUUID())
                        .changed());

        assertThat(outcomes).allMatch(outcome -> !outcome.failed());
        assertThat(outcomes.stream().filter(Concurrently.Outcome::value).count())
                .isEqualTo(1);
        assertThat(killSwitch.current().version()).isEqualTo(before + 1);
    }

    @Test
    void withTheSwitchOffTheJobCancelsNothing() throws Exception {
        UUID quoted = order(activePerson(), "QUOTED");

        assertThat(killSwitch.cancelUnsentWhileActive()).isZero();
        assertThat(orderState(quoted)).isEqualTo("QUOTED");
    }

    @Test
    void theReasonGoesFromOneToFiveHundredCharacters() {
        assertThat(catchThrowable(() -> transactions.execute(status -> killSwitch.activate(UUID.randomUUID(), "  "))))
                .isInstanceOfSatisfying(RealAdminRefused.class, refused -> assertThat(refused.code())
                        .isEqualTo("VALIDATION_FAILED"));
        assertThat(catchThrowable(
                        () -> transactions.execute(status -> killSwitch.activate(UUID.randomUUID(), "x".repeat(501)))))
                .isInstanceOf(RealAdminRefused.class);
        assertThat(killSwitch.current().active()).isFalse();
    }

    /** BR2.5: the open restore marker is a global pause too, RESTORE_CHECK_PENDING since it was opened. */
    @Test
    void anOpenRestoreCheckPausesEveryWalletSinceItWasOpened() throws Exception {
        OpsPerson person = activePerson();
        Instant opened = clock.instant().minus(Duration.ofMinutes(1));
        jdbc.sql("insert into real_restore_marker (marker_id, phase, opened_at) values (gen_random_uuid(),"
                        + " 'CHECK_PENDING', :at)")
                .param("at", Timestamp.from(opened))
                .update();

        RealStateView view =
                state.readRealState(person.tenantId(), person.userId()).orElseThrow();

        assertThat(view.pausedSince()).containsEntry(RealPauseReason.RESTORE_CHECK_PENDING, opened);
        assertThat(wallets.recheckBeforeSend(person.tenantId(), person.userId(), RealOperationMode.AUTO_FULL, true))
                .isEqualTo(new RealExecutionWallet.Recheck.Cancelled(RealExecutionWallet.RecheckReason.PAUSED));
    }

    private UUID stoppedBot(OpsPerson person) {
        UUID bot = UUID.randomUUID();
        jdbc.sql("insert into bot_owner (tenant_id, owner_user_id, created_at) values (:t, :u, now()) on conflict do"
                        + " nothing")
                .param("t", person.tenantId())
                .param("u", person.userId())
                .update();
        jdbc.sql("insert into bot (bot_id, tenant_id, owner_user_id, name, template_id, template_version, state,"
                        + " created_at, updated_at, stopped_at) values (:b, :t, :u, 'robo', 'MOMENTUM_VOLUME', '1',"
                        + " 'STOPPED', now(), now(), now())")
                .param("b", bot)
                .param("t", person.tenantId())
                .param("u", person.userId())
                .update();
        return bot;
    }
}
