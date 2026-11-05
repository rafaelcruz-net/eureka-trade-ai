package com.memetrade.bots.domain;

import static com.memetrade.bots.api.BotState.ACTIVE;
import static com.memetrade.bots.api.BotState.CLOSING_PENDING;
import static com.memetrade.bots.api.BotState.DRAFT;
import static com.memetrade.bots.api.BotState.PAUSED;
import static com.memetrade.bots.api.BotState.STOPPED;
import static com.memetrade.bots.api.BotState.VALIDATED;
import static com.memetrade.bots.api.PauseReason.ADMIN_RELEASED;
import static com.memetrade.bots.api.PauseReason.DAILY_LOSS;
import static com.memetrade.bots.api.PauseReason.DRAWDOWN;
import static com.memetrade.bots.api.PauseReason.PERSON;
import static com.memetrade.bots.api.PauseReason.SYSTEM;
import static com.memetrade.bots.api.PauseReason.WAITING_ACTIVE_SLOT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.bots.api.BotState;
import com.memetrade.bots.api.PauseReason;
import com.memetrade.bots.domain.BotRuleViolation.Code;
import com.memetrade.bots.domain.BotStateMachine.Change;
import com.memetrade.bots.domain.BotStateMachine.MidnightChange;
import com.memetrade.bots.domain.BotStateMachine.ResumeCheck;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Catalogue L of functional-spec.md, written BEFORE {@link BotStateMachine} (Passo 5; US4.4, US4.7, US4.8, US7.2;
 * BR2.1, BR2.4, BR2.5, BR2.6; NFR4.1). Every combination of command and state (L9), the pause reasons, the resume after
 * the daily loss (L6) and the UTC midnight in memory (L3 to L5). Exact equality only.
 */
@Tag("u5")
class BotLifecycleCatalogTest {

    private static final ResumeCheck FREE = new ResumeCheck(false, 0, new BigDecimal("50.000000"), Optional.empty());

    /** The only accepted transitions of the annex "Transições do bot" (BR2.1), per command and starting state. */
    private static final Map<String, Map<BotState, BotState>> TABLE = Map.of(
            "validate", Map.of(DRAFT, VALIDATED),
            "edit", Map.of(DRAFT, DRAFT, VALIDATED, DRAFT),
            "start", Map.of(VALIDATED, ACTIVE),
            "pause", Map.of(ACTIVE, PAUSED, PAUSED, PAUSED),
            "resume", Map.of(PAUSED, ACTIVE),
            "stop", Map.of(ACTIVE, STOPPED, PAUSED, STOPPED),
            "stopPending", Map.of(ACTIVE, CLOSING_PENDING, PAUSED, CLOSING_PENDING),
            "closingCompleted", Map.of(CLOSING_PENDING, STOPPED),
            "dailyLoss", Map.of(ACTIVE, PAUSED, PAUSED, PAUSED),
            "system", Map.of(ACTIVE, PAUSED, PAUSED, PAUSED));

    private static final Map<String, Function<BotStatus, Change>> COMMANDS = Map.of(
            "validate", BotStateMachine::validate,
            "edit", BotStateMachine::edit,
            "start", status -> BotStateMachine.start(status, false, 0),
            "pause", BotStateMachine::pause,
            "resume", status -> BotStateMachine.resume(status, FREE),
            "stop", status -> BotStateMachine.stop(status, false),
            "stopPending", status -> BotStateMachine.stop(status, true),
            "closingCompleted", BotStateMachine::closingCompleted,
            "dailyLoss", status -> BotStateMachine.pauseBy(status, DAILY_LOSS),
            "system", status -> BotStateMachine.pauseBy(status, SYSTEM));

    /** L9: every command from every state; outside the table it is INVALID_TRANSITION and nothing changes. */
    @ParameterizedTest
    @EnumSource(BotState.class)
    void l9EveryCombinationOfCommandAndStateFollowsTheTable(BotState state) {
        BotStatus from = state == PAUSED ? BotStatus.of(PAUSED, PERSON) : BotStatus.of(state);
        for (var command : COMMANDS.entrySet()) {
            BotState expected = TABLE.get(command.getKey()).get(state);
            if (expected == null) {
                assertThatThrownBy(() -> command.getValue().apply(from))
                        .as(command.getKey() + " from " + state)
                        .isInstanceOf(BotRuleViolation.class)
                        .extracting(failure -> ((BotRuleViolation) failure).code())
                        .isEqualTo(Code.INVALID_TRANSITION);
            } else {
                Change change = command.getValue().apply(from);
                assertThat(change.before()).as(command.getKey()).isEqualTo(from);
                assertThat(change.after().state())
                        .as(command.getKey() + " from " + state)
                        .isEqualTo(expected);
            }
        }
    }

    /** AC4.4.3 and AC4.12.5: the two examples of the story, DRAFT and STOPPED never reach ACTIVE. */
    @Test
    void neitherADraftNorAStoppedBotEverReachesActive() {
        assertThatThrownBy(() -> BotStateMachine.start(BotStatus.of(DRAFT), false, 0))
                .isInstanceOf(BotRuleViolation.class);
        assertThatThrownBy(() -> BotStateMachine.start(BotStatus.of(STOPPED), false, 0))
                .isInstanceOf(BotRuleViolation.class);
        assertThatThrownBy(() -> BotStateMachine.resume(BotStatus.of(STOPPED), FREE))
                .isInstanceOf(BotRuleViolation.class);
    }

    /** L1: with 2 active bots the start is accepted; with 3 it is ACTIVE_BOT_LIMIT and the bot stays VALIDATED. */
    @Test
    void l1TheThirdActiveBotIsAcceptedAndTheFourthIsRefused() {
        assertThat(BotStateMachine.start(BotStatus.of(VALIDATED), false, 2).after())
                .isEqualTo(BotStatus.of(ACTIVE));
        assertThatThrownBy(() -> BotStateMachine.start(BotStatus.of(VALIDATED), false, 3))
                .extracting(failure -> ((BotRuleViolation) failure).code())
                .isEqualTo(Code.ACTIVE_BOT_LIMIT);
    }

    /** BR2.2, AC7.2.3: with the kill switch on, the start is PAUSED_BY_SYSTEM before any count. */
    @Test
    void theStartIsRefusedWhileTheKillSwitchIsOn() {
        assertThatThrownBy(() -> BotStateMachine.start(BotStatus.of(VALIDATED), true, 0))
                .extracting(failure -> ((BotRuleViolation) failure).code())
                .isEqualTo(Code.PAUSED_BY_SYSTEM);
    }

    /** BR2.3, AC4.8.3: pausing a bot already paused by the person is idempotent (no change, so no second event). */
    @Test
    void pausingTwiceChangesOnceAndKeepsTheOtherReasons() {
        Change first = BotStateMachine.pause(BotStatus.of(ACTIVE));
        Change again = BotStateMachine.pause(first.after());
        Change onTopOfLoss = BotStateMachine.pause(BotStatus.of(PAUSED, DAILY_LOSS));

        assertThat(first.changed()).isTrue();
        assertThat(first.after()).isEqualTo(BotStatus.of(PAUSED, PERSON));
        assertThat(again.changed()).isFalse();
        assertThat(onTopOfLoss.after()).isEqualTo(BotStatus.of(PAUSED, PERSON, DAILY_LOSS));
    }

    /** BR2.5: the automatic reasons accumulate, a repeated trigger changes nothing. */
    @Test
    void theTriggersOfTheBarrierAccumulateAndARepeatedTriggerChangesNothing() {
        Change loss = BotStateMachine.pauseBy(BotStatus.of(ACTIVE), DAILY_LOSS);
        Change drawdown = BotStateMachine.pauseBy(loss.after(), DRAWDOWN);
        Change repeated = BotStateMachine.pauseBy(drawdown.after(), DRAWDOWN);

        assertThat(drawdown.after()).isEqualTo(BotStatus.of(PAUSED, DAILY_LOSS, DRAWDOWN));
        assertThat(repeated.changed()).isFalse();
        assertThatThrownBy(() -> BotStateMachine.pauseBy(BotStatus.of(ACTIVE), PERSON))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** BR2.4: resume removes the person, administration, waiting, drawdown (free, Q9) and allowed daily loss reasons. */
    @Test
    void theResumeRemovesEveryRemovableReasonAndReturnsToActive() {
        BotStatus paused = BotStatus.of(PAUSED, PERSON, DRAWDOWN, ADMIN_RELEASED, WAITING_ACTIVE_SLOT);

        Change change = BotStateMachine.resume(paused, FREE);

        assertThat(change.after()).isEqualTo(BotStatus.of(ACTIVE));
    }

    /** L6: day loss 50 with the new limit 50.000001 is accepted; with the limit 50 it is refused with the reason. */
    @Test
    void l6TheResumeAfterTheDailyLossNeedsALimitAboveTheLossOfTheDay() {
        BotStatus paused = BotStatus.of(PAUSED, DAILY_LOSS);
        BigDecimal loss = new BigDecimal("50.000000");

        Change accepted = BotStateMachine.resume(
                paused, new ResumeCheck(false, 0, new BigDecimal("50.000001"), Optional.of(loss)));

        assertThat(accepted.after()).isEqualTo(BotStatus.of(ACTIVE));
        assertThatThrownBy(() -> BotStateMachine.resume(
                        paused, new ResumeCheck(false, 0, new BigDecimal("50.000000"), Optional.of(loss))))
                .extracting(failure -> ((BotRuleViolation) failure).code())
                .isEqualTo(Code.DAILY_LOSS_LIMIT_REACHED);
    }

    /** BR2.4, AC7.2.3: SYSTEM still on the bot, or the kill switch on, refuses the resume. */
    @Test
    void theResumeIsRefusedWhileTheSystemPauseHolds() {
        assertThatThrownBy(() -> BotStateMachine.resume(BotStatus.of(PAUSED, SYSTEM, PERSON), FREE))
                .extracting(failure -> ((BotRuleViolation) failure).code())
                .isEqualTo(Code.PAUSED_BY_SYSTEM);
        assertThatThrownBy(() -> BotStateMachine.resume(
                        BotStatus.of(PAUSED, PERSON), new ResumeCheck(true, 0, BigDecimal.ONE, Optional.empty())))
                .extracting(failure -> ((BotRuleViolation) failure).code())
                .isEqualTo(Code.PAUSED_BY_SYSTEM);
    }

    /** AC4.8.4: with 3 active bots the resume is refused with the limit. */
    @Test
    void theResumeIsRefusedWithThreeActiveBots() {
        assertThatThrownBy(() -> BotStateMachine.resume(
                        BotStatus.of(PAUSED, PERSON), new ResumeCheck(false, 3, BigDecimal.ONE, Optional.empty())))
                .extracting(failure -> ((BotRuleViolation) failure).code())
                .isEqualTo(Code.ACTIVE_BOT_LIMIT);
    }

    /** L8: SYSTEM becomes ADMIN_RELEASED when the kill switch is switched off; the resume works only after that. */
    @Test
    void l8TheSystemReasonBecomesAdminReleasedAndOnlyThenTheResumeWorks() {
        BotStatus paused = BotStateMachine.pauseBy(BotStatus.of(ACTIVE), SYSTEM).after();
        assertThatThrownBy(() -> BotStateMachine.resume(paused, FREE)).isInstanceOf(BotRuleViolation.class);

        Change lifted = BotStateMachine.liftSystem(paused);
        Change liftedAgain = BotStateMachine.liftSystem(lifted.after());

        assertThat(lifted.after()).isEqualTo(BotStatus.of(PAUSED, ADMIN_RELEASED));
        assertThat(liftedAgain.changed()).isFalse();
        assertThat(BotStateMachine.resume(lifted.after(), FREE).after()).isEqualTo(BotStatus.of(ACTIVE));
    }

    /** L3: paused only by the daily loss, at midnight with 2 active bots, it returns to ACTIVE. */
    @Test
    void l3AtMidnightABotPausedOnlyByTheDailyLossReturnsToActive() {
        MidnightChange change = BotStateMachine.midnight(BotStatus.of(PAUSED, DAILY_LOSS), 2);

        assertThat(change.after()).isEqualTo(BotStatus.of(ACTIVE));
        assertThat(change.dailyLossCleared()).isTrue();
        assertThat(change.resumed()).isTrue();
        assertThat(change.waitingForSlot()).isFalse();
    }

    /** L4: with DAILY_LOSS and PERSON, only DAILY_LOSS leaves; the bot stays PAUSED. */
    @Test
    void l4AtMidnightOnlyTheDailyLossLeaves() {
        MidnightChange change = BotStateMachine.midnight(BotStatus.of(PAUSED, DAILY_LOSS, PERSON), 0);

        assertThat(change.after()).isEqualTo(BotStatus.of(PAUSED, PERSON));
        assertThat(change.dailyLossCleared()).isTrue();
        assertThat(change.resumed()).isFalse();
    }

    /** L5: with 3 active bots, DAILY_LOSS leaves and WAITING_ACTIVE_SLOT enters; the bot stays PAUSED. */
    @Test
    void l5AtMidnightWithThreeActiveBotsTheBotWaitsForASlot() {
        MidnightChange change = BotStateMachine.midnight(BotStatus.of(PAUSED, DAILY_LOSS), 3);

        assertThat(change.after()).isEqualTo(BotStatus.of(PAUSED, WAITING_ACTIVE_SLOT));
        assertThat(change.dailyLossCleared()).isTrue();
        assertThat(change.resumed()).isFalse();
        assertThat(change.waitingForSlot()).isTrue();
    }

    /** BR2.6: a bot without the daily loss is not touched by the midnight (repeatable without double effect). */
    @Test
    void theMidnightLeavesEveryOtherBotUntouched() {
        for (BotStatus status : List.of(
                BotStatus.of(ACTIVE), BotStatus.of(PAUSED, PERSON), BotStatus.of(STOPPED), BotStatus.of(DRAFT))) {
            MidnightChange change = BotStateMachine.midnight(status, 0);
            assertThat(change.after()).isEqualTo(status);
            assertThat(change.dailyLossCleared()).isFalse();
        }
    }

    /** BR6.1, AC4.7.6: resumesAt is the next UTC midnight only when DAILY_LOSS is the only reason. */
    @Test
    void theRestartInstantIsTheNextUtcMidnightOnlyWithTheDailyLossAlone() {
        Instant justBefore = Instant.parse("2026-10-28T23:59:59Z");

        assertThat(BotStateMachine.resumesAt(BotStatus.of(PAUSED, DAILY_LOSS), justBefore))
                .contains(Instant.parse("2026-10-29T00:00:00Z"));
        assertThat(BotStateMachine.resumesAt(BotStatus.of(PAUSED, DAILY_LOSS), Instant.parse("2026-10-29T00:00:00Z")))
                .contains(Instant.parse("2026-10-30T00:00:00Z"));
        assertThat(BotStateMachine.resumesAt(BotStatus.of(PAUSED, DAILY_LOSS, PERSON), justBefore))
                .isEmpty();
        assertThat(BotStateMachine.resumesAt(BotStatus.of(ACTIVE), justBefore)).isEmpty();
    }

    /** BR2.7: the stop clears the reasons, with or without pending positions. */
    @Test
    void theStopClearsThePauseReasons() {
        assertThat(BotStateMachine.stop(BotStatus.of(PAUSED, PERSON, DAILY_LOSS), false)
                        .after())
                .isEqualTo(BotStatus.of(STOPPED));
        assertThat(BotStateMachine.stop(BotStatus.of(PAUSED, SYSTEM), true).after())
                .isEqualTo(BotStatus.of(CLOSING_PENDING));
    }

    /** BR5.1: the limits change only on ACTIVE or PAUSED bots; any other state is INVALID_TRANSITION. */
    @ParameterizedTest
    @EnumSource(BotState.class)
    void theLimitsAreEditableOnlyOnActiveOrPausedBots(BotState state) {
        BotStatus status = state == PAUSED ? BotStatus.of(PAUSED, PERSON) : BotStatus.of(state);
        if (state == ACTIVE || state == PAUSED) {
            assertThatCode(() -> BotStateMachine.requireLimitsEditable(status)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> BotStateMachine.requireLimitsEditable(status))
                    .isInstanceOf(BotRuleViolation.class);
        }
    }

    /** entities.md: PAUSED if and only if there is a reason; a status that breaks it cannot exist. */
    @Test
    void aStatusIsPausedIfAndOnlyIfItHasAReason() {
        assertThatThrownBy(() -> new BotStatus(PAUSED, Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BotStatus(ACTIVE, EnumSet.of(PERSON)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(BotStatus.of(PAUSED, SYSTEM, PERSON).orderedReasons())
                .containsExactly(PauseReason.PERSON, PauseReason.SYSTEM);
    }
}
