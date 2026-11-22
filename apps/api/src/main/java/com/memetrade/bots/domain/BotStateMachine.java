package com.memetrade.bots.domain;

import static com.memetrade.bots.api.BotState.ACTIVE;
import static com.memetrade.bots.api.BotState.CLOSING_PENDING;
import static com.memetrade.bots.api.BotState.DRAFT;
import static com.memetrade.bots.api.BotState.PAUSED;
import static com.memetrade.bots.api.BotState.STOPPED;
import static com.memetrade.bots.api.BotState.VALIDATED;

import com.memetrade.bots.api.BotState;
import com.memetrade.bots.api.PauseReason;
import com.memetrade.bots.domain.BotRuleViolation.Code;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The lifecycle of a PAPER bot (BR2.1 to BR2.8, annex "Transições do bot"): a pure function of the status and of what
 * the command checks. Only the transitions of the annex are accepted; any other is {@link Code#INVALID_TRANSITION}
 * without change. Pause reasons accumulate and each one leaves only by its own rule (BR2.5); the bot is ACTIVE only
 * without any reason. A repeated command that changes nothing returns a {@link Change} with {@code changed() == false},
 * so the caller writes no event (AC4.8.3).
 */
public final class BotStateMachine {

    public static final int ACTIVE_LIMIT = 3;

    /** The reasons the barrier of U6 or the kill switch add (never PERSON, which only the pause command adds). */
    private static final Set<PauseReason> AUTOMATIC =
            EnumSet.of(PauseReason.DAILY_LOSS, PauseReason.DRAWDOWN, PauseReason.SYSTEM);

    /** The reasons a resume removes once its checks pass (BR2.4; DRAWDOWN freely, Q9). */
    private static final Set<PauseReason> RESUMABLE = EnumSet.of(
            PauseReason.PERSON,
            PauseReason.ADMIN_RELEASED,
            PauseReason.WAITING_ACTIVE_SLOT,
            PauseReason.DAILY_LOSS,
            PauseReason.DRAWDOWN);

    private BotStateMachine() {}

    /** One transition: the status before and after; {@code changed} is false for an idempotent repetition. */
    public record Change(BotStatus before, BotStatus after) {
        public boolean changed() {
            return !before.equals(after);
        }
    }

    /** The result of the UTC midnight for one bot (BR2.6). */
    public record MidnightChange(BotStatus before, BotStatus after, boolean dailyLossCleared, boolean resumed) {
        public boolean waitingForSlot() {
            return after.has(PauseReason.WAITING_ACTIVE_SLOT) && !before.has(PauseReason.WAITING_ACTIVE_SLOT);
        }
    }

    /**
     * What a resume checks besides the status (BR2.4).
     *
     * @param systemPaused the kill switch as U5 recorded it, read with the shared lock
     * @param activeCount the ACTIVE bots of the person, counted under the owner lock
     * @param maxDailyLoss the daily loss limit of the version in force
     * @param dayLoss the realized loss of the UTC day with fees; required when the bot has DAILY_LOSS
     */
    public record ResumeCheck(
            boolean systemPaused, long activeCount, BigDecimal maxDailyLoss, Optional<BigDecimal> dayLoss) {
        public ResumeCheck {
            Objects.requireNonNull(maxDailyLoss, "maxDailyLoss");
            Objects.requireNonNull(dayLoss, "dayLoss");
        }

        /**
         * 261121-paper-real-unico U1 (BR1.9): the check of a bot without limits of its own (a REAL bot). It never has
         * the PAPER reason DAILY_LOSS (the daily loss of the REAL pauses the bot wallet), so no daily loss limit nor day
         * loss is compared; a DAILY_LOSS on such a bot is refused as a programming error.
         */
        public static ResumeCheck withoutBotLimits(boolean systemPaused, long activeCount) {
            return new ResumeCheck(systemPaused, activeCount, NO_BOT_LIMIT, Optional.empty());
        }
    }

    /** The marker of {@link ResumeCheck#withoutBotLimits}: never compared, because the day loss is absent. */
    private static final BigDecimal NO_BOT_LIMIT = new BigDecimal("-1");

    /** BR1.7: the draft becomes VALIDATED when steps 1 and 2 are valid (checked by the caller). */
    public static Change validate(BotStatus status) {
        require(status, DRAFT);
        return new Change(status, BotStatus.of(VALIDATED));
    }

    /** BR1.7: editing a VALIDATED bot returns it to DRAFT; a DRAFT stays DRAFT. */
    public static Change edit(BotStatus status) {
        require(status, DRAFT, VALIDATED);
        return new Change(status, BotStatus.of(DRAFT));
    }

    /** BR2.2: VALIDATED to ACTIVE, refused with the kill switch on or with 3 ACTIVE bots of the person. */
    public static Change start(BotStatus status, boolean systemPaused, long activeCount) {
        require(status, VALIDATED);
        if (systemPaused) {
            throw new BotRuleViolation(Code.PAUSED_BY_SYSTEM, "The kill switch is on; the bot cannot start");
        }
        if (activeCount >= ACTIVE_LIMIT) {
            throw new BotRuleViolation(Code.ACTIVE_BOT_LIMIT, ACTIVE_LIMIT + " of " + ACTIVE_LIMIT + " active bots");
        }
        return new Change(status, BotStatus.of(ACTIVE));
    }

    /** BR2.3: the person's pause adds PERSON to an ACTIVE or PAUSED bot; already there, nothing changes. */
    public static Change pause(BotStatus status) {
        require(status, ACTIVE, PAUSED);
        return new Change(status, withReason(status, PauseReason.PERSON));
    }

    /** BR2.5, BR3.4, BR7.1: a trigger of the barrier (DAILY_LOSS, DRAWDOWN) or the kill switch (SYSTEM). */
    public static Change pauseBy(BotStatus status, PauseReason reason) {
        if (!AUTOMATIC.contains(reason)) {
            throw new IllegalArgumentException("Only DAILY_LOSS, DRAWDOWN or SYSTEM pause a bot automatically");
        }
        require(status, ACTIVE, PAUSED);
        return new Change(status, withReason(status, reason));
    }

    /**
     * BR2.4: PAUSED to ACTIVE when no reason remains. In order: SYSTEM on the bot or the kill switch on is
     * PAUSED_BY_SYSTEM; DAILY_LOSS with a limit not above the day loss is DAILY_LOSS_LIMIT_REACHED; 3 ACTIVE bots is
     * ACTIVE_BOT_LIMIT. Otherwise every removable reason leaves.
     */
    public static Change resume(BotStatus status, ResumeCheck check) {
        require(status, PAUSED);
        if (status.has(PauseReason.SYSTEM) || check.systemPaused()) {
            throw new BotRuleViolation(Code.PAUSED_BY_SYSTEM, "Paused by the system");
        }
        if (status.has(PauseReason.DAILY_LOSS)) {
            BigDecimal loss = check.dayLoss()
                    .orElseThrow(() -> new IllegalArgumentException("The day loss is required to resume DAILY_LOSS"));
            if (check.maxDailyLoss().compareTo(loss) <= 0) {
                throw new BotRuleViolation(
                        Code.DAILY_LOSS_LIMIT_REACHED,
                        "The loss of the day is " + loss.toPlainString(),
                        loss.toPlainString());
            }
        }
        if (check.activeCount() >= ACTIVE_LIMIT) {
            throw new BotRuleViolation(Code.ACTIVE_BOT_LIMIT, ACTIVE_LIMIT + " of " + ACTIVE_LIMIT + " active bots");
        }
        EnumSet<PauseReason> remaining = copy(status.reasons());
        remaining.removeAll(RESUMABLE);
        return new Change(status, statusWith(remaining));
    }

    /** BR7.2: SYSTEM becomes ADMIN_RELEASED; a bot without SYSTEM does not change. No bot is resumed. */
    public static Change liftSystem(BotStatus status) {
        if (!status.has(PauseReason.SYSTEM)) {
            return new Change(status, status);
        }
        EnumSet<PauseReason> reasons = copy(status.reasons());
        reasons.remove(PauseReason.SYSTEM);
        reasons.add(PauseReason.ADMIN_RELEASED);
        return new Change(status, BotStatus.paused(reasons));
    }

    /**
     * BR2.6: at UTC midnight only DAILY_LOSS leaves. Without any other reason, the bot is ACTIVE when the person has
     * fewer than 3 ACTIVE bots, otherwise it waits with WAITING_ACTIVE_SLOT. A bot without DAILY_LOSS is untouched.
     */
    public static MidnightChange midnight(BotStatus status, long activeCount) {
        if (status.state() != PAUSED || !status.has(PauseReason.DAILY_LOSS)) {
            return new MidnightChange(status, status, false, false);
        }
        EnumSet<PauseReason> remaining = copy(status.reasons());
        remaining.remove(PauseReason.DAILY_LOSS);
        if (!remaining.isEmpty()) {
            return new MidnightChange(status, BotStatus.paused(remaining), true, false);
        }
        if (activeCount < ACTIVE_LIMIT) {
            return new MidnightChange(status, BotStatus.of(ACTIVE), true, true);
        }
        return new MidnightChange(status, BotStatus.of(PAUSED, PauseReason.WAITING_ACTIVE_SLOT), true, false);
    }

    /** BR2.7: ACTIVE or PAUSED to STOPPED, or to CLOSING_PENDING with pending positions; the reasons are cleared. */
    public static Change stop(BotStatus status, boolean pendingPositions) {
        require(status, ACTIVE, PAUSED);
        return new Change(status, BotStatus.of(pendingPositions ? CLOSING_PENDING : STOPPED));
    }

    /** BR2.8: CLOSING_PENDING to STOPPED when U7 completed the closing. */
    public static Change closingCompleted(BotStatus status) {
        require(status, CLOSING_PENDING);
        return new Change(status, BotStatus.of(STOPPED));
    }

    /**
     * u5-anonymization (BR5.2, BR5.3): ACTIVE, PAUSED and CLOSING_PENDING go straight to STOPPED, without any closing
     * and without reasons; DRAFT, VALIDATED and STOPPED do not change ({@code changed() == false}).
     */
    public static Change anonymizeStop(BotStatus status) {
        BotState state = status.state();
        if (state == ACTIVE || state == PAUSED || state == CLOSING_PENDING) {
            return new Change(status, BotStatus.of(STOPPED));
        }
        return new Change(status, status);
    }

    /** BR5.1: a new limits version only for an ACTIVE or PAUSED bot. */
    public static void requireLimitsEditable(BotStatus status) {
        require(status, ACTIVE, PAUSED);
    }

    /** BR6.1, AC4.7.6: the next UTC midnight after {@code now}, only when DAILY_LOSS is the only reason. */
    public static Optional<Instant> resumesAt(BotStatus status, Instant now) {
        if (status.state() != PAUSED || !status.reasons().equals(Set.of(PauseReason.DAILY_LOSS))) {
            return Optional.empty();
        }
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        return Optional.of(today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    private static void require(BotStatus status, BotState... accepted) {
        for (BotState state : accepted) {
            if (status.state() == state) {
                return;
            }
        }
        throw new BotRuleViolation(Code.INVALID_TRANSITION, "Transition not allowed from " + status.state());
    }

    private static BotStatus withReason(BotStatus status, PauseReason reason) {
        EnumSet<PauseReason> reasons = copy(status.reasons());
        reasons.add(reason);
        return BotStatus.paused(reasons);
    }

    private static BotStatus statusWith(EnumSet<PauseReason> reasons) {
        return reasons.isEmpty() ? BotStatus.of(ACTIVE) : BotStatus.paused(reasons);
    }

    private static EnumSet<PauseReason> copy(Set<PauseReason> reasons) {
        return reasons.isEmpty() ? EnumSet.noneOf(PauseReason.class) : EnumSet.copyOf(reasons);
    }
}
