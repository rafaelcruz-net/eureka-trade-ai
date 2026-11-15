package com.memetrade.realexecution.domain;

import static com.memetrade.realexecution.api.RealOrderState.AMBIGUOUS;
import static com.memetrade.realexecution.api.RealOrderState.CANCELLED;
import static com.memetrade.realexecution.api.RealOrderState.CONFIRMED;
import static com.memetrade.realexecution.api.RealOrderState.FAILED;
import static com.memetrade.realexecution.api.RealOrderState.PROPOSED;
import static com.memetrade.realexecution.api.RealOrderState.QUOTED;
import static com.memetrade.realexecution.api.RealOrderState.SENT;
import static com.memetrade.realexecution.api.RealOrderState.SIGNED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.realexecution.api.RealFailureCode;
import com.memetrade.realexecution.api.RealOrderState;
import com.memetrade.realexecution.api.RealSide;
import com.memetrade.realexecution.domain.RealOrderStateMachine.ChainStatus;
import com.memetrade.realexecution.domain.RealOrderStateMachine.DeadlineVerdict;
import com.memetrade.realexecution.domain.RealOrderStateMachine.NetworkVerdict;
import com.memetrade.realexecution.domain.RealOrderStateMachine.SigningStep;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 5 of the U4, written BEFORE the state machine (functional-spec.md "Máquina de estados — RealOrder", S1 to S30;
 * BR2.2, BR3.1, BR3.2, BR4.2, BR5.1, BR5.2, BR7.1; U4-NFR4.1, U4-NFR4.2, U4-NFR4.5). Pure: no clock, no database.
 */
@Tag("u4-real-execution")
class RealOrderStateMachineTest {

    private static final Instant T0 = Instant.parse("2026-11-15T12:00:00Z");
    private static final Duration SIXTY = Duration.ofSeconds(60);

    // ----- edges of the state machine -----

    @Test
    void theHappyPathOfS1IsAllowedStepByStep() {
        assertThat(RealOrderStateMachine.canMove(PROPOSED, QUOTED)).isTrue();
        assertThat(RealOrderStateMachine.canMove(QUOTED, SIGNED)).isTrue();
        assertThat(RealOrderStateMachine.canMove(SIGNED, SENT)).isTrue();
        assertThat(RealOrderStateMachine.canMove(SENT, CONFIRMED)).isTrue();
    }

    @Test
    void everyEdgeOfTheSpecificationAndNothingElseIsAllowed() {
        Set<String> allowed = Set.of(
                "PROPOSED>QUOTED",
                "PROPOSED>FAILED",
                "PROPOSED>CANCELLED",
                "QUOTED>QUOTED",
                "QUOTED>SIGNED",
                "QUOTED>FAILED",
                "QUOTED>CANCELLED",
                "SIGNED>SENT",
                "SIGNED>CANCELLED",
                "SENT>CONFIRMED",
                "SENT>FAILED",
                "SENT>AMBIGUOUS",
                "AMBIGUOUS>CONFIRMED",
                "AMBIGUOUS>FAILED");
        for (RealOrderState from : RealOrderState.values()) {
            for (RealOrderState to : RealOrderState.values()) {
                assertThat(RealOrderStateMachine.canMove(from, to))
                        .as(from + " -> " + to)
                        .isEqualTo(allowed.contains(from + ">" + to));
            }
        }
    }

    @Test
    void aFinalStateNeverMovesAgain() {
        for (RealOrderState end : EnumSet.of(CONFIRMED, FAILED, CANCELLED)) {
            assertThat(end.isFinal()).isTrue();
            for (RealOrderState to : RealOrderState.values()) {
                assertThat(RealOrderStateMachine.canMove(end, to)).isFalse();
            }
        }
        assertThatThrownBy(() -> RealOrderStateMachine.requireMove(CONFIRMED, FAILED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONFIRMED");
    }

    @Test
    void s14AnAmbiguousOrderIsNeverSentNorSignedAgain() {
        assertThat(RealOrderStateMachine.canMove(AMBIGUOUS, SENT)).isFalse();
        assertThat(RealOrderStateMachine.canMove(AMBIGUOUS, SIGNED)).isFalse();
        assertThat(RealOrderStateMachine.canMove(AMBIGUOUS, CANCELLED)).isFalse();
        assertThat(RealOrderStateMachine.mayResend(AMBIGUOUS, 10, 100)).isFalse();
    }

    @Test
    void s24ASentOrderIsNeverCancelled() {
        assertThat(RealOrderStateMachine.canMove(SENT, CANCELLED)).isFalse();
        assertThat(RealOrderState.UNSENT).doesNotContain(SENT, AMBIGUOUS);
    }

    @Test
    void s3TheStatesInProgressAreTheFiveOfBr22() {
        assertThat(RealOrderState.IN_PROGRESS).containsExactlyInAnyOrder(PROPOSED, QUOTED, SIGNED, SENT, AMBIGUOUS);
    }

    @Test
    void s17ACancelFromOutsideTouchesOnlyProposedAndQuoted() {
        assertThat(RealOrderStateMachine.CANCEL_UNSENT).containsExactlyInAnyOrder(PROPOSED, QUOTED);
        assertThat(RealOrderStateMachine.RECHECK_CANCELLABLE).containsExactlyInAnyOrder(PROPOSED, QUOTED, SIGNED);
    }

    @Test
    void theReservationOfABuyIsReleasedOnlyInAFinalState() {
        assertThat(RealOrderStateMachine.releasesReserve(RealSide.BUY, CONFIRMED))
                .isTrue();
        assertThat(RealOrderStateMachine.releasesReserve(RealSide.BUY, FAILED)).isTrue();
        assertThat(RealOrderStateMachine.releasesReserve(RealSide.BUY, CANCELLED))
                .isTrue();
        // BR5.3: an ambiguous order keeps its reservation until it ends.
        assertThat(RealOrderStateMachine.releasesReserve(RealSide.BUY, AMBIGUOUS))
                .isFalse();
        assertThat(RealOrderStateMachine.releasesReserve(RealSide.BUY, SENT)).isFalse();
        assertThat(RealOrderStateMachine.releasesReserve(RealSide.SELL, CONFIRMED))
                .isFalse();
    }

    // ----- one transaction per order (BR3.1, U4-NFR4.1) -----

    @Test
    void s4ASentOrderIsResentWithTheSameTransactionOnlyWhileItsBlockhashIsValid() {
        assertThat(RealOrderStateMachine.mayResend(SENT, 100, 100)).isTrue();
        assertThat(RealOrderStateMachine.mayResend(SENT, 101, 100)).isFalse();
        assertThat(RealOrderStateMachine.mayResend(SIGNED, 1, 100)).isFalse();
    }

    @Test
    void aSignatureIsAskedOnlyForAQuotedOrderAndTheMessageIsRebuiltOnlyBeforeTheFirstAsk() {
        assertThat(RealOrderStateMachine.mayAskSignature(QUOTED)).isTrue();
        assertThat(RealOrderStateMachine.mayAskSignature(SIGNED)).isFalse();
        assertThat(RealOrderStateMachine.mayAskSignature(SENT)).isFalse();
        assertThat(RealOrderStateMachine.mayRebuildMessage(QUOTED, Optional.empty()))
                .isTrue();
        assertThat(RealOrderStateMachine.mayRebuildMessage(QUOTED, Optional.of(T0)))
                .isFalse();
        assertThat(RealOrderStateMachine.mayRebuildMessage(SIGNED, Optional.empty()))
                .isFalse();
    }

    // ----- quote freshness before signing (BR3.2, S2) -----

    @Test
    void s2AFreshQuoteIsSignedAStaleOneIsQuotedAgainOnceAndThenFails() {
        Duration max = Duration.ofSeconds(10);

        assertThat(RealOrderStateMachine.beforeSigning(T0, T0.plusSeconds(10), false, max))
                .isEqualTo(SigningStep.SIGN);
        assertThat(RealOrderStateMachine.beforeSigning(T0, T0.plusSeconds(11), false, max))
                .isEqualTo(SigningStep.REQUOTE);
        assertThat(RealOrderStateMachine.beforeSigning(T0, T0.plusSeconds(11), true, max))
                .isEqualTo(SigningStep.FAIL_QUOTE_EXPIRED);
    }

    // ----- the signing session of the SIGNED mode (BR4.2, S8, S25, S29) -----

    @Test
    void s8AnUnclaimedSessionPastItsDeadlineIsCancelled() {
        DeadlineVerdict verdict = RealOrderStateMachine.onSigningDeadline(
                T0.plusSeconds(8),
                T0.plusSeconds(8),
                Optional.empty(),
                T0,
                Duration.ofSeconds(15),
                Duration.ofSeconds(10));

        assertThat(verdict).isEqualTo(DeadlineVerdict.CANCEL_DEADLINE);
        assertThat(RealOrderStateMachine.onSigningDeadline(
                        T0.plusMillis(7_999),
                        T0.plusSeconds(8),
                        Optional.empty(),
                        T0,
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(10)))
                .isEqualTo(DeadlineVerdict.NONE);
    }

    @Test
    void s25AClaimedSessionIsNeverCancelledByTheDeadline() {
        Optional<Instant> claimed = Optional.of(T0.plusSeconds(7));

        assertThat(RealOrderStateMachine.onSigningDeadline(
                        T0.plusSeconds(9),
                        T0.plusSeconds(8),
                        claimed,
                        T0,
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(10)))
                .isEqualTo(DeadlineVerdict.NONE);
    }

    @Test
    void s29AClaimStuckFor15SecondsIsRepeatedOrFailsWhenTheQuoteIsOld() {
        Instant quoteAt = T0;
        Optional<Instant> claimed = Optional.of(T0.plusSeconds(2));

        // 15 s after the claim the quote is 17 s old: nothing is sent, FAILED QUOTE_EXPIRED.
        assertThat(RealOrderStateMachine.onSigningDeadline(
                        T0.plusSeconds(17),
                        T0.plusSeconds(8),
                        claimed,
                        quoteAt,
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(10)))
                .isEqualTo(DeadlineVerdict.FAIL_QUOTE_EXPIRED);
        // With a larger quote age allowed (configuration), the same request is repeated.
        assertThat(RealOrderStateMachine.onSigningDeadline(
                        T0.plusSeconds(17),
                        T0.plusSeconds(8),
                        claimed,
                        quoteAt,
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(20)))
                .isEqualTo(DeadlineVerdict.RETRY_SIGNING);
        assertThat(RealOrderStateMachine.onSigningDeadline(
                        T0.plusSeconds(16),
                        T0.plusSeconds(8),
                        claimed,
                        quoteAt,
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(20)))
                .isEqualTo(DeadlineVerdict.NONE);
    }

    // ----- the network (BR5.1, BR5.2, S5, S11, S12, S13, S24) -----

    @Test
    void s1AFinalizedSentOrderIsConfirmed() {
        assertThat(network(SENT, ChainStatus.FINALIZED, 5, 10, 100)).isEqualTo(NetworkVerdict.move(CONFIRMED, null));
    }

    @Test
    void s5ARejectionForSlippageIsFailedSlippageAndAnyOtherRejectionChainRejected() {
        assertThat(network(SENT, ChainStatus.FAILED_SLIPPAGE, 5, 10, 100))
                .isEqualTo(NetworkVerdict.move(FAILED, RealFailureCode.SLIPPAGE_EXCEEDED));
        assertThat(network(SENT, ChainStatus.FAILED, 5, 10, 100))
                .isEqualTo(NetworkVerdict.move(FAILED, RealFailureCode.CHAIN_REJECTED));
    }

    @Test
    void s11SixtySecondsWithoutAFinalAnswerMakeTheOrderAmbiguous() {
        assertThat(network(SENT, ChainStatus.PENDING, 59, 10, 100)).isEqualTo(NetworkVerdict.stay());
        assertThat(network(SENT, ChainStatus.PENDING, 60, 10, 100)).isEqualTo(NetworkVerdict.move(AMBIGUOUS, null));
        assertThat(network(SENT, ChainStatus.UNAVAILABLE, 61, 10, 100)).isEqualTo(NetworkVerdict.move(AMBIGUOUS, null));
    }

    @Test
    void s24ASentOrderAbsentPastItsBlockhashFailsAndIsNeverCancelled() {
        assertThat(network(SENT, ChainStatus.NOT_FOUND, 30, 101, 100))
                .isEqualTo(NetworkVerdict.move(FAILED, RealFailureCode.BLOCKHASH_EXPIRED));
        assertThat(network(SENT, ChainStatus.NOT_FOUND, 30, 100, 100)).isEqualTo(NetworkVerdict.resend());
    }

    @Test
    void s12AnAmbiguousOrderIsResolvedOnlyByTheNetwork() {
        assertThat(network(AMBIGUOUS, ChainStatus.FINALIZED, 300, 10, 100))
                .isEqualTo(NetworkVerdict.move(CONFIRMED, null));
        assertThat(network(AMBIGUOUS, ChainStatus.NOT_FOUND, 300, 101, 100))
                .isEqualTo(NetworkVerdict.move(FAILED, RealFailureCode.BLOCKHASH_EXPIRED));
        assertThat(network(AMBIGUOUS, ChainStatus.NOT_FOUND, 300, 100, 100)).isEqualTo(NetworkVerdict.stay());
        assertThat(network(AMBIGUOUS, ChainStatus.PENDING, 300, 101, 100)).isEqualTo(NetworkVerdict.stay());
    }

    @Test
    void s13AnAmbiguousOrderStaysAmbiguousWithTheRpcDown() {
        assertThat(network(AMBIGUOUS, ChainStatus.UNAVAILABLE, 900, 0, 100)).isEqualTo(NetworkVerdict.stay());
    }

    @Test
    void theNetworkVerdictOfAnOrderNotOnTheNetworkIsAProgrammingError() {
        assertThatThrownBy(() -> network(QUOTED, ChainStatus.FINALIZED, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static NetworkVerdict network(
            RealOrderState state, ChainStatus status, long secondsSinceSend, long height, long lastValid) {
        return RealOrderStateMachine.onNetwork(
                state, status, Duration.ofSeconds(secondsSinceSend), height, lastValid, SIXTY);
    }
}
