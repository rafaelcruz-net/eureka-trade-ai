package com.memetrade.administration.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One row of the append-only history (entities.md KillSwitchChange, BR2.9). */
public record KillSwitchChange(
        UUID changeId,
        long version,
        SwitchState from,
        SwitchState to,
        UUID adminUserId,
        String reason,
        Instant changedAt,
        String correlationId) {

    public KillSwitchChange {
        Objects.requireNonNull(changeId, "changeId");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(adminUserId, "adminUserId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(changedAt, "changedAt");
        Objects.requireNonNull(correlationId, "correlationId");
        if (from == to) {
            throw new IllegalArgumentException("A change always goes from one state to the other");
        }
        if (version < 1) {
            throw new IllegalArgumentException("The first change has version 1");
        }
    }

    @Override
    public String toString() {
        return "KillSwitchChange[version=" + version + ", from=" + from + ", to=" + to + "]";
    }
}
