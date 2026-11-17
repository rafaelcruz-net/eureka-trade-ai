package com.memetrade.realexits.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.botwallet.api.RealChain;
import com.memetrade.botwallet.api.RealKillSwitchState;
import com.memetrade.realexits.AbstractRealExitsIT;
import com.memetrade.realexits.domain.ExitSide;
import com.memetrade.realexits.domain.TriggerPort.CancelResult;
import com.memetrade.realexits.domain.TriggerPort.ProviderStatus;
import com.memetrade.testsupport.SyntheticIds;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Step 8 of the U7, written BEFORE the code (BR6, S16 to S18, S36, S38, S39, U7-NFR3.2, U7-NFR4.5): every minute the
 * cancellation job cancels the conditionals of the kill switch, of a change of mode out of AUTO_EXITS and of a revoked
 * release (PENDING_SIGNATURE directly, OPEN through CANCELLING), and sends again every cancellation not confirmed yet.
 * A conditional never goes back to OPEN; a fill before the cancellation counts (BR6.6). Nothing here looks at the
 * exits flag, the REAL switch nor the pauses: the cancellation works like the withdrawal (BR1.3).
 */
@Tag("u7-exits-and-fiscal")
class ConditionalCancelJobIT extends AbstractRealExitsIT {

    private static final UUID ADMIN = UUID.randomUUID();

    @Autowired
    private ConditionalCancelJob cancels;

    @Autowired
    private RealKillSwitchState killSwitch;

    @Autowired
    private com.memetrade.botwallet.api.RealExecutionWallet wallets;

    @AfterEach
    void switchOff() {
        killSwitch.deactivate(ADMIN, "fim do teste");
    }

    private void mode(Setup setup, String mode) {
        jdbc.sql("update real_preferences set operation_mode = :mode where bot_wallet_id = :w")
                .param("mode", mode)
                .param("w", setup.botWalletId())
                .update();
    }

    /** S16, U7-NFR3.2: the kill switch moves the OPEN conditional to CANCELLING in the first run, then CANCELLED. */
    @Test
    void s16TheKillSwitchCancelsAnOpenConditionalThroughCancelling() {
        Setup setup = realBot("AUTO_EXITS");
        UUID conditional = conditional(setup, "OPEN", 5_000L);
        trigger.cancels.put(conditional, new CancelResult.Submitted(SyntheticIds.signature()));
        killSwitch.activate(ADMIN, "teste das saídas");

        cancels.runOnce();
        assertThat(conditionalState(conditional)).isEqualTo("CANCELLING");
        assertThat(cancelReason(conditional)).isEqualTo("KILL_SWITCH");

        trigger.cancels.put(conditional, new CancelResult.Cancelled(java.util.Optional.of(SyntheticIds.signature())));
        clock.advance(Duration.ofMinutes(1));
        cancels.runOnce();
        assertThat(conditionalState(conditional)).isEqualTo("CANCELLED");
    }

    /** S36, BR6.5: a conditional waiting for the signature is CANCELLED without a transaction. */
    @Test
    void s36APendingSignatureIsCancelledWithoutATransaction() {
        Setup setup = realBot("AUTO_EXITS");
        UUID conditional = conditional(setup, "PENDING_SIGNATURE", 5_000L);
        killSwitch.activate(ADMIN, "teste das saídas");

        cancels.runOnce();

        assertThat(conditionalState(conditional)).isEqualTo("CANCELLED");
        assertThat(cancelReason(conditional)).isEqualTo("KILL_SWITCH");
        assertThat(trigger.calls).hasValue(0);
        assertThat(jdbc.sql("select signing_message from real_conditional_order where conditional_order_id = :id")
                        .param("id", conditional)
                        .query(String.class)
                        .optional())
                .as("the signing session is closed")
                .isEmpty();
    }

    /** S17, BR6.2: leaving AUTO_EXITS (to SIGNED or to AUTO_FULL) cancels the conditionals of the person. */
    @Test
    void s17LeavingAutoExitsCancelsTheConditionalsOfThePerson() {
        Setup signed = realBot("AUTO_EXITS");
        Setup full = realBot("AUTO_EXITS");
        UUID first = conditional(signed, "OPEN", 5_000L);
        UUID second = conditional(full, "OPEN", 5_000L);
        mode(signed, "SIGNED");
        mode(full, "AUTO_FULL");

        cancels.runOnce();

        assertThat(conditionalState(first)).isEqualTo("CANCELLED");
        assertThat(cancelReason(first)).isEqualTo("MODE_CHANGE");
        assertThat(conditionalState(second)).isEqualTo("CANCELLED");
        assertThat(cancelReason(second)).isEqualTo("MODE_CHANGE");
    }

    /** S18, BR6.3: a revoked release cancels the conditionals of the person. */
    @Test
    void s18ARevokedReleaseCancelsTheConditionalsOfThePerson() {
        Setup setup = realBot("AUTO_EXITS");
        UUID conditional = conditional(setup, "OPEN", 5_000L);
        jdbc.sql("update real_release set revoked_at = now() where user_id = :u")
                .param("u", setup.userId())
                .update();

        cancels.runOnce();

        assertThat(conditionalState(conditional)).isEqualTo("CANCELLED");
        assertThat(cancelReason(conditional)).isEqualTo("RELEASE_REVOKED");
    }

    /** team.md: the job acts on the person of each row only; a person in AUTO_EXITS keeps her conditional. */
    @Test
    void aPersonStillInAutoExitsKeepsHerConditional() {
        Setup kept = realBot("AUTO_EXITS");
        Setup lowered = realBot("AUTO_EXITS");
        UUID mine = conditional(kept, "OPEN", 5_000L);
        UUID theirs = conditional(lowered, "OPEN", 5_000L);
        mode(lowered, "SIGNED");

        cancels.runOnce();

        assertThat(conditionalState(mine)).isEqualTo("OPEN");
        assertThat(conditionalState(theirs)).isEqualTo("CANCELLED");
    }

    /** BR6.4, U7-NFR4.5: a cancellation not confirmed is sent again every minute and never goes back to OPEN. */
    @Test
    void aCancellationNotConfirmedIsSentAgainEveryMinuteAndNeverGoesBackToOpen() {
        Setup setup = realBot("AUTO_EXITS");
        UUID conditional = conditional(setup, "CANCELLING", 5_000L);
        trigger.cancels.put(conditional, new CancelResult.Submitted(SyntheticIds.signature()));

        for (int minute = 0; minute < 16; minute++) {
            cancels.runOnce();
            clock.advance(Duration.ofMinutes(1));
        }

        assertThat(conditionalState(conditional)).isEqualTo("CANCELLING");
        assertThat(jdbc.sql("select cancel_attempts from real_conditional_order where conditional_order_id = :id")
                        .param("id", conditional)
                        .query(Integer.class)
                        .single())
                .isEqualTo(16);
    }

    /** infrastructure-specification.md "Rollback": the job cancels with the exits flag off (it only stops creations). */
    @Test
    void theCancellationWorksWithTheExitsFlagOff() {
        Setup setup = realBot("AUTO_EXITS");
        UUID conditional = conditional(setup, "CANCELLING", 5_000L);
        exits.enabled.set(false);

        cancels.runOnce();

        assertThat(conditionalState(conditional)).isEqualTo("CANCELLED");
    }

    /** S39, BR1.3: with the kill switch on and the person paused, the cancellation is still sent. */
    @Test
    void s39TheCancellationIsSentWithTheKillSwitchOnAndThePersonPaused() {
        Setup setup = realBot("AUTO_EXITS");
        UUID conditional = conditional(setup, "CANCELLING", 5_000L);
        jdbc.sql("update real_preferences set paused_reasons = array['USER'] where bot_wallet_id = :w")
                .param("w", setup.botWalletId())
                .update();
        killSwitch.activate(ADMIN, "teste das saídas");

        cancels.runOnce();

        assertThat(trigger.calls.get()).isPositive();
        assertThat(conditionalState(conditional)).isEqualTo("CANCELLED");
    }

    /** S20, BR6.6: the provider says the conditional filled before the cancellation confirmed: the fill counts. */
    @Test
    void s20AFillBeforeTheCancellationConfirmedCounts() {
        Setup setup = realBot("AUTO_EXITS");
        position(wallet(setup), setup.mint(), 5_000L, 1_000_000L);
        UUID conditional = conditional(setup, "CANCELLING", 5_000L);
        String signature = SyntheticIds.signature();
        trigger.statuses.put(conditional, new ProviderStatus.Filled(ExitSide.STOP, signature));
        chain.finalize(signature);
        chain.effect(signature, new RealChain.SwapEffect(1_200_000L, 0L, 5_000L, false));

        cancels.runOnce();

        assertThat(conditionalState(conditional)).isEqualTo("FILLED");
        assertThat(conditionalEntries(conditional)).containsEntry("SWAP_FILL_OUT", 1_200_000L);
    }

    /** U7-NFR4.6: the provider down leaves the conditional CANCELLING for the next run. */
    @Test
    void theProviderDownLeavesTheConditionalCancelling() {
        Setup setup = realBot("AUTO_EXITS");
        UUID conditional = conditional(setup, "CANCELLING", 5_000L);
        trigger.down.set(true);

        cancels.runOnce();

        assertThat(conditionalState(conditional)).isEqualTo("CANCELLING");
    }

    /** BR6.5: a signing session past its deadline expires the conditional. */
    @Test
    void aSigningSessionPastItsDeadlineExpires() {
        Setup setup = realBot("AUTO_EXITS");
        UUID conditional = conditional(setup, "PENDING_SIGNATURE", 5_000L);
        clock.advance(Duration.ofMinutes(2));

        cancels.runOnce();

        assertThat(conditionalState(conditional)).isEqualTo("EXPIRED");
    }

    /** BR6.8: the withdrawal never depends on the job: a CANCELLING conditional blocks no withdrawal (U7-NFR4.7). */
    @Test
    void theSaleOfTheRestOfTheFreeBalanceIsNeverBlockedByTheJob() {
        Setup setup = realBot("AUTO_EXITS");
        conditional(setup, "CANCELLING", 5_000L);
        trigger.down.set(true);
        cancels.runOnce();

        java.util.Optional<UUID> withdrawal = wallets.requestWithdrawalAll(
                setup.tenantId(), setup.userId(), UUID.randomUUID().toString());

        assertThat(withdrawal).isPresent();
    }
}
