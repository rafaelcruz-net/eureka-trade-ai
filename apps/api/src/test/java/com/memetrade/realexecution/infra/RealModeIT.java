package com.memetrade.realexecution.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.botwallet.application.BotWalletService;
import com.memetrade.botwallet.application.BotWalletViews;
import com.memetrade.botwallet.domain.ConsentKind;
import com.memetrade.botwallet.domain.OperationMode;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.realexecution.AbstractRealExecutionIT;
import com.memetrade.realexecution.api.CancelScope;
import com.memetrade.realexecution.api.RealFailureCode;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Step 12 of the U4, written BEFORE the code (F7, F10, BR7.1, BR7.2; S17, S18): lowering the mode is immediate and
 * cancels the orders not sent yet (PROPOSED and QUOTED) in the same transaction, while a SENT order goes on; raising it
 * needs the consent of the main wallet and is refused with an AMBIGUOUS order; M6 cancelUnsent of everybody (the kill
 * switch of the U5) never touches an order already signed or sent.
 */
@Tag("u4-real-execution")
class RealModeIT extends AbstractRealExecutionIT {

    @Autowired
    private RealModeService modes;

    @Autowired
    private BotWalletService botWallets;

    private static Principal person(Setup setup) {
        return new Principal(setup.tenantId(), setup.userId(), Role.BETA_USER);
    }

    private String mode(Setup setup) {
        return jdbc.sql("select operation_mode from real_preferences where bot_wallet_id = :w")
                .param("w", setup.botWalletId())
                .query(String.class)
                .single();
    }

    private static ErrorCode codeOf(Runnable call) {
        try {
            call.run();
        } catch (ApiException refused) {
            return refused.code();
        }
        throw new AssertionError("the call was not refused");
    }

    @Test
    void s17LoweringCancelsTheQuotedAndProposedOrdersAndASentOrderGoesOn() {
        Setup setup = realBot("AUTO_FULL");
        UUID sentRequest = pendingBuy(setup, 10_000_000L);
        consumer.runOnce();
        UUID sent = orderOf(sentRequest);
        driver.runOnce();
        assertThat(state(sent)).isEqualTo("SENT");
        UUID otherBot = realBotRow(setup.tenantId(), setup.userId());
        var proposed = transactions.execute(
                status -> decisionStep.submit(com.memetrade.bots.application.RealDecisionStep.Candidate.buy(
                        setup.tenantId(),
                        setup.userId(),
                        otherBot,
                        UUID.randomUUID(),
                        setup.mint(),
                        10_000_000L,
                        "x")));
        consumer.runOnce();
        UUID unsent = orderOf(proposed.decisionRequestId().orElseThrow());

        modes.change(person(setup), "SIGNED", null, null);

        assertThat(mode(setup)).isEqualTo("SIGNED");
        assertThat(state(unsent)).isEqualTo("CANCELLED");
        assertThat(failureCode(unsent)).isEqualTo("MODE_LOWERED");
        assertThat(entries(unsent)).containsEntry("RESERVE_RELEASE", 10_000_000L);
        assertThat(state(sent)).isEqualTo("SENT");
        assertThat(signer.raisedModes).containsEntry(setup.bot().address(), "SIGNED");
    }

    @Test
    void s18RaisingWithoutAConsentIsRefusedConsentInvalid() {
        Setup setup = realBot("SIGNED");

        assertThat(codeOf(() -> modes.change(person(setup), "AUTO_FULL", null, null)))
                .isEqualTo(ErrorCode.CONSENT_INVALID);
        assertThat(mode(setup)).isEqualTo("SIGNED");
    }

    @Test
    void s18RaisingWithTheConsentOfTheMainWalletIsApplied() {
        Setup setup = realBot("SIGNED");
        BotWalletViews.ChallengeView challenge =
                botWallets.issueChallenge(person(setup), ConsentKind.RAISE_MODE, OperationMode.AUTO_FULL, null);

        modes.change(
                person(setup),
                "AUTO_FULL",
                challenge.challengeId(),
                setup.main().sign(challenge.message()));

        assertThat(mode(setup)).isEqualTo("AUTO_FULL");
    }

    @Test
    void raisingWithAnAmbiguousOrderIsRefusedOrderAmbiguous() {
        Setup setup = realBot("AUTO_FULL");
        UUID request = pendingBuy(setup, 10_000_000L);
        consumer.runOnce();
        UUID order = orderOf(request);
        driver.runOnce();
        clock.advance(Duration.ofSeconds(60));
        confirmation.runOnce();
        assertThat(state(order)).isEqualTo("AMBIGUOUS");
        modes.change(person(setup), "SIGNED", null, null);

        assertThat(codeOf(() -> modes.change(person(setup), "AUTO_FULL", UUID.randomUUID(), "x")))
                .isEqualTo(ErrorCode.ORDER_AMBIGUOUS);
    }

    @Test
    void theCancellationOfEverybodyTouchesOnlyTheOrdersNotSentYet() {
        Setup first = realBot("AUTO_FULL");
        Setup second = realBot("AUTO_FULL");
        UUID firstRequest = pendingBuy(first, 10_000_000L);
        UUID secondRequest = pendingBuy(second, 10_000_000L);
        consumer.runOnce();
        UUID proposed = orderOf(firstRequest);
        UUID other = orderOf(secondRequest);
        jdbc.sql("update real_order set lease_owner = 'test', lease_until = 'infinity' where real_order_id = :id")
                .param("id", proposed)
                .update();
        driver.runOnce();
        assertThat(state(other)).isEqualTo("SENT");

        int cancelled = port.cancelUnsent(CancelScope.all(), RealFailureCode.KILL_SWITCH);

        assertThat(cancelled).isGreaterThanOrEqualTo(1);
        assertThat(state(proposed)).isEqualTo("CANCELLED");
        assertThat(state(other)).isEqualTo("SENT");
    }
}
