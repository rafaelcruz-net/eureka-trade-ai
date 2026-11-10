package com.memetrade.bots.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.bots.api.BotState;
import com.memetrade.bots.api.PauseReason;
import java.util.EnumSet;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Passo 2 of u5-anonymization, written BEFORE the rule (BR5.2, BR5.3; functional-spec.md F2 step 1): the anonymization
 * takes ACTIVE, PAUSED and CLOSING_PENDING straight to STOPPED, without a pending closing and without any reason, and
 * leaves DRAFT, VALIDATED and STOPPED as they are, so no closing order is ever created.
 */
@Tag("u5-anonymization")
class BotAnonymizeStopTest {

    @Test
    void anActiveBotStopsAtOnce() {
        BotStateMachine.Change change = BotStateMachine.anonymizeStop(BotStatus.of(BotState.ACTIVE));

        assertThat(change.after()).isEqualTo(BotStatus.of(BotState.STOPPED));
        assertThat(change.changed()).isTrue();
    }

    @Test
    void aBotPausedByThePersonStopsAndLosesItsReason() {
        BotStateMachine.Change change =
                BotStateMachine.anonymizeStop(BotStatus.of(BotState.PAUSED, PauseReason.PERSON));

        assertThat(change.after().state()).isEqualTo(BotState.STOPPED);
        assertThat(change.after().reasons()).isEmpty();
    }

    @Test
    void aBotPausedForSeveralReasonsAlsoStopsWithoutAnyReason() {
        BotStatus paused =
                BotStatus.paused(EnumSet.of(PauseReason.SYSTEM, PauseReason.DAILY_LOSS, PauseReason.DRAWDOWN));

        BotStateMachine.Change change = BotStateMachine.anonymizeStop(paused);

        assertThat(change.before()).isEqualTo(paused);
        assertThat(change.after()).isEqualTo(BotStatus.of(BotState.STOPPED));
    }

    @Test
    void aBotWaitingForItsClosingStopsWithoutTheClosing() {
        BotStateMachine.Change change = BotStateMachine.anonymizeStop(BotStatus.of(BotState.CLOSING_PENDING));

        assertThat(change.after()).isEqualTo(BotStatus.of(BotState.STOPPED));
        assertThat(change.changed()).isTrue();
    }

    @Test
    void aDraftStaysADraft() {
        BotStateMachine.Change change = BotStateMachine.anonymizeStop(BotStatus.of(BotState.DRAFT));

        assertThat(change.after()).isEqualTo(BotStatus.of(BotState.DRAFT));
        assertThat(change.changed()).isFalse();
    }

    @Test
    void aValidatedBotStaysValidated() {
        BotStateMachine.Change change = BotStateMachine.anonymizeStop(BotStatus.of(BotState.VALIDATED));

        assertThat(change.after()).isEqualTo(BotStatus.of(BotState.VALIDATED));
        assertThat(change.changed()).isFalse();
    }

    @Test
    void aStoppedBotIsAlreadyFinalAndRepeatingChangesNothing() {
        BotStateMachine.Change first = BotStateMachine.anonymizeStop(BotStatus.of(BotState.ACTIVE));

        BotStateMachine.Change again = BotStateMachine.anonymizeStop(first.after());

        assertThat(again.changed()).isFalse();
        assertThat(again.after()).isEqualTo(BotStatus.of(BotState.STOPPED));
    }

    @ParameterizedTest
    @EnumSource(BotState.class)
    void noStateEverEndsInAPendingClosingOrPaused(BotState state) {
        BotStatus status = state == BotState.PAUSED ? BotStatus.of(state, PauseReason.PERSON) : BotStatus.of(state);

        BotStateMachine.Change change = BotStateMachine.anonymizeStop(status);

        assertThat(change.after().state()).isNotIn(BotState.CLOSING_PENDING, BotState.PAUSED, BotState.ACTIVE);
    }

    @ParameterizedTest
    @EnumSource(
            value = BotState.class,
            names = {"ACTIVE", "PAUSED", "CLOSING_PENDING"})
    void everyRunningStateStops(BotState state) {
        BotStatus status =
                state == BotState.PAUSED ? BotStatus.of(state, PauseReason.ADMIN_RELEASED) : BotStatus.of(state);

        assertThat(BotStateMachine.anonymizeStop(status).after().state()).isEqualTo(BotState.STOPPED);
    }
}
