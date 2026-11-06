package com.memetrade.administration.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The single row of the kill switch as read (entities.md KillSwitchState). {@code changedBy}, {@code changedAt} and
 * {@code reason} are empty only before the first change. {@code version} grows at every change of state and never at a
 * mark of reconciliation.
 */
public record KillSwitchSnapshot(
        SwitchState state,
        Optional<UUID> changedBy,
        Optional<Instant> changedAt,
        Optional<String> reason,
        int pendingBots,
        boolean reconcileNeeded,
        long version) {

    public KillSwitchSnapshot {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(changedBy, "changedBy");
        Objects.requireNonNull(changedAt, "changedAt");
        Objects.requireNonNull(reason, "reason");
        if (pendingBots < 0 || version < 0) {
            throw new IllegalArgumentException("pendingBots and version are never negative");
        }
    }

    /** The row created by migration 0011: OFF, never changed. */
    public static KillSwitchSnapshot initial() {
        return new KillSwitchSnapshot(
                SwitchState.OFF, Optional.empty(), Optional.empty(), Optional.empty(), 0, false, 0);
    }

    @Override
    public String toString() {
        // The reason never reaches a log (observability-design.md: allowed fields of the administration module).
        return "KillSwitchSnapshot[state=" + state + ", pendingBots=" + pendingBots + ", reconcileNeeded="
                + reconcileNeeded + ", version=" + version + "]";
    }
}
