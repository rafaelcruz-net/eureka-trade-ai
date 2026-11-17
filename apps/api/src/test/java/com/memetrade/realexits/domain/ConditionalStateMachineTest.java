package com.memetrade.realexits.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Step 4 of the U7, written BEFORE the code, from entities.md "Máquina de estados" and the scenarios S1, S2, S11, S12,
 * S13, S16, S20 and S36: the allowed transitions, the final states and the triggers of a cancellation.
 */
@Tag("u7-exits-and-fiscal")
class ConditionalStateMachineTest {

    @ParameterizedTest
    @CsvSource({
        "PENDING_SIGNATURE, OPEN",
        "PENDING_SIGNATURE, EXPIRED",
        "PENDING_SIGNATURE, FAILED",
        "PENDING_SIGNATURE, CANCELLED",
        "OPEN, FILLED",
        "OPEN, CANCELLING",
        "OPEN, EXPIRED",
        "CANCELLING, CANCELLED",
        "CANCELLING, FILLED"
    })
    void theTransitionsOfTheTableAreAllowed(ConditionalState from, ConditionalState to) {
        assertThat(ConditionalStateMachine.allows(from, to)).isTrue();
        assertThat(ConditionalStateMachine.require(from, to)).isEqualTo(to);
    }

    @ParameterizedTest
    @CsvSource({
        "PENDING_SIGNATURE, FILLED",
        "PENDING_SIGNATURE, CANCELLING",
        "OPEN, PENDING_SIGNATURE",
        "OPEN, CANCELLED",
        "OPEN, FAILED",
        "CANCELLING, OPEN",
        "CANCELLING, EXPIRED",
        "CANCELLING, FAILED"
    })
    void everyOtherTransitionIsRefused(ConditionalState from, ConditionalState to) {
        assertThat(ConditionalStateMachine.allows(from, to)).isFalse();
        assertThatThrownBy(() -> ConditionalStateMachine.require(from, to))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(from.name())
                .hasMessageContaining(to.name());
    }

    @ParameterizedTest
    @EnumSource(
            value = ConditionalState.class,
            names = {"FILLED", "CANCELLED", "EXPIRED", "FAILED"})
    void aFinalStateNeverMovesAgain(ConditionalState finalState) {
        assertThat(finalState.isFinal()).isTrue();
        for (ConditionalState to : ConditionalState.values()) {
            assertThat(ConditionalStateMachine.allows(finalState, to))
                    .as("%s -> %s", finalState, to)
                    .isFalse();
        }
    }

    @Test
    void theStatesThatAreNotFinalAreTheOnesOfThePartialUniqueIndex() {
        assertThat(ConditionalState.NOT_FINAL)
                .containsExactlyInAnyOrder(
                        ConditionalState.PENDING_SIGNATURE, ConditionalState.OPEN, ConditionalState.CANCELLING);
        assertThat(EnumSet.complementOf(EnumSet.copyOf(ConditionalState.NOT_FINAL)))
                .allMatch(ConditionalState::isFinal);
    }

    @Test
    void cancellingIsReachedOnlyFromOpenAndNeverGoesBackToOpen() {
        Set<ConditionalState> into = EnumSet.noneOf(ConditionalState.class);
        for (ConditionalState from : ConditionalState.values()) {
            if (ConditionalStateMachine.allows(from, ConditionalState.CANCELLING)) {
                into.add(from);
            }
        }
        assertThat(into).containsExactly(ConditionalState.OPEN);
        assertThat(ConditionalStateMachine.allows(ConditionalState.CANCELLING, ConditionalState.OPEN))
                .isFalse();
    }

    /** BR6.5, S36: a PENDING_SIGNATURE is cancelled without a transaction; an OPEN one waits in CANCELLING. */
    @Test
    void theCancellationOfAPendingSignatureIsDirectAndOfAnOpenOneGoesThroughCancelling() {
        assertThat(ConditionalStateMachine.cancellationTarget(ConditionalState.PENDING_SIGNATURE))
                .contains(ConditionalState.CANCELLED);
        assertThat(ConditionalStateMachine.cancellationTarget(ConditionalState.OPEN))
                .contains(ConditionalState.CANCELLING);
        assertThat(ConditionalStateMachine.cancellationTarget(ConditionalState.CANCELLING))
                .as("already being cancelled: nothing to move")
                .isEmpty();
        assertThat(ConditionalStateMachine.cancellationTarget(ConditionalState.FILLED))
                .isEmpty();
    }

    /** BR5.4, BR6.6, S20: a fill is accepted while OPEN or CANCELLING, and wins over the cancellation. */
    @Test
    void aFillIsAcceptedFromOpenAndFromCancelling() {
        assertThat(ConditionalStateMachine.FILLABLE)
                .containsExactlyInAnyOrder(ConditionalState.OPEN, ConditionalState.CANCELLING);
    }
}
