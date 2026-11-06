package com.memetrade.administration.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Persistence port of the kill switch (tables {@code kill_switch_state} and {@code kill_switch_change}). The fencing
 * rules of reliability-design.md (R-09) live here:
 *
 * <ul>
 *   <li>{@link #markReconcile()} is an unconditional UPDATE anyone may run; it never changes {@code version} and stamps
 *       {@code reconcile_marked_at} with the database clock;
 *   <li>{@link #recordOutcome} is run only by the holder of the apply lock, and only changes the row when the version is
 *       still the one read under the lock AND no mark was made after the start of that application.
 * </ul>
 */
public interface KillSwitchStore {

    /** The current row, without locking. */
    KillSwitchSnapshot read();

    /** The current row, locked until the end of the caller's transaction ({@code SELECT ... FOR UPDATE}). */
    KillSwitchSnapshot lockForUpdate();

    /**
     * A new state with its version (inside the caller's transaction, after {@link #lockForUpdate()}); OFF also clears
     * {@code pending_bots} (BR2.2).
     */
    void writeState(StateWrite write);

    /** One row of the history, in the same transaction as {@link #writeState}. */
    void appendChange(KillSwitchChange change);

    /** {@code reconcile_needed = true} and a fresh mark; never touches {@code version} nor {@code pending_bots}. */
    void markReconcile();

    /** State, version and the database clock read in ONE statement, at the start of an application under the lock. */
    Fence fence();

    /**
     * Writes {@code pending_bots} and clears {@code reconcile_needed} only when the version is still the fenced one and
     * no reconciliation was asked for after the fence; returns false when nothing was written.
     */
    boolean recordOutcome(Fence fence, int pendingBots);

    /** The written fields of a change of state. */
    record StateWrite(SwitchState state, java.util.UUID changedBy, String reason, Instant changedAt, long version) {
        public StateWrite {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(changedBy, "changedBy");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(changedAt, "changedAt");
        }
    }

    /** What the applier read under the lock: the state to apply, its version and the start of the application. */
    record Fence(SwitchState state, long version, Instant startedAt) {
        public Fence {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(startedAt, "startedAt");
        }
    }
}
