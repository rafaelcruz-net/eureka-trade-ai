package com.memetrade.administration.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Unit-test double of {@link KillSwitchStore} with the same rules as the table: one row, an append-only history with a
 * unique version, an unconditional mark and the fenced write of the outcome. The "database clock" of the fence and of
 * the mark is a counter that only grows, so two events never share an instant even with a fixed test clock.
 */
public final class InMemoryKillSwitchStore implements KillSwitchStore {

    private final List<KillSwitchChange> history = new ArrayList<>();
    private final AtomicLong databaseClock = new AtomicLong(1_000);
    private KillSwitchSnapshot row = KillSwitchSnapshot.initial();
    private Instant markedAt;
    private int locks;

    public InMemoryKillSwitchStore() {}

    public InMemoryKillSwitchStore(Clock ignored) {}

    @Override
    public synchronized KillSwitchSnapshot read() {
        return row;
    }

    @Override
    public synchronized KillSwitchSnapshot lockForUpdate() {
        locks++;
        return row;
    }

    @Override
    public synchronized void writeState(StateWrite write) {
        if (write.version() != row.version() + 1) {
            throw new IllegalStateException("version must grow by one");
        }
        row = new KillSwitchSnapshot(
                write.state(),
                Optional.of(write.changedBy()),
                Optional.of(write.changedAt()),
                Optional.of(write.reason()),
                write.state() == SwitchState.OFF ? 0 : row.pendingBots(),
                row.reconcileNeeded(),
                write.version());
    }

    @Override
    public synchronized void appendChange(KillSwitchChange change) {
        if (history.stream().anyMatch(existing -> existing.version() == change.version())) {
            throw new IllegalStateException("duplicate version");
        }
        history.add(change);
    }

    @Override
    public synchronized void markReconcile() {
        markedAt = tick();
        row = with(row.pendingBots(), true, row.version());
    }

    @Override
    public synchronized Fence fence() {
        return new Fence(row.state(), row.version(), tick());
    }

    @Override
    public synchronized boolean recordOutcome(Fence fence, int pendingBots) {
        boolean markedAfter = markedAt != null && !markedAt.isBefore(fence.startedAt());
        if (row.version() != fence.version() || markedAfter) {
            return false;
        }
        row = with(pendingBots, false, row.version());
        return true;
    }

    /** Test-only: sets pending bots and the flag directly, like a previous partial application. */
    public synchronized void force(int pendingBots, boolean reconcileNeeded) {
        row = with(pendingBots, reconcileNeeded, row.version());
    }

    /** Test-only: a change of version by "another request", without history. */
    public synchronized void bumpVersion(SwitchState state) {
        row = new KillSwitchSnapshot(
                state,
                row.changedBy(),
                row.changedAt(),
                row.reason(),
                row.pendingBots(),
                row.reconcileNeeded(),
                row.version() + 1);
    }

    public synchronized List<KillSwitchChange> history() {
        return List.copyOf(history);
    }

    public synchronized int locks() {
        return locks;
    }

    private KillSwitchSnapshot with(int pendingBots, boolean reconcileNeeded, long version) {
        return new KillSwitchSnapshot(
                row.state(), row.changedBy(), row.changedAt(), row.reason(), pendingBots, reconcileNeeded, version);
    }

    private Instant tick() {
        return Instant.ofEpochMilli(databaseClock.incrementAndGet());
    }
}
