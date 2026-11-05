package com.memetrade.bots.domain;

import com.memetrade.bots.api.BotState;
import com.memetrade.bots.api.PauseReason;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * State and pause reasons of a bot, as one value (entities.md: {@code state = PAUSED} if and only if the reasons are
 * not empty). The reasons are a set; their order is the declaration order of {@link PauseReason}.
 */
public record BotStatus(BotState state, Set<PauseReason> reasons) {

    public BotStatus {
        Objects.requireNonNull(state, "state");
        reasons = reasons == null || reasons.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(reasons));
        if ((state == BotState.PAUSED) != !reasons.isEmpty()) {
            throw new IllegalArgumentException("A bot is PAUSED if and only if it has a pause reason");
        }
    }

    public static BotStatus of(BotState state, PauseReason... reasons) {
        return new BotStatus(state, reasons.length == 0 ? Set.of() : EnumSet.copyOf(Set.of(reasons)));
    }

    public static BotStatus paused(Collection<PauseReason> reasons) {
        return new BotStatus(BotState.PAUSED, Set.copyOf(reasons));
    }

    public boolean has(PauseReason reason) {
        return reasons.contains(reason);
    }

    /** The reasons in the fixed declaration order, for storage and display. */
    public java.util.List<PauseReason> orderedReasons() {
        return reasons.isEmpty() ? java.util.List.of() : java.util.List.copyOf(EnumSet.copyOf(reasons));
    }
}
