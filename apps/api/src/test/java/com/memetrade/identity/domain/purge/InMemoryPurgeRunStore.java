package com.memetrade.identity.domain.purge;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/** Unit-test double of {@link PurgeRunStore}; {@link #failWith(RuntimeException)} simulates the database being down. */
public final class InMemoryPurgeRunStore implements PurgeRunStore {

    private final Map<IdentityPurge, PurgeRun> runs = new EnumMap<>(IdentityPurge.class);
    private RuntimeException failure;

    public void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    @Override
    public synchronized Optional<PurgeRun> find(IdentityPurge purge) {
        if (failure != null) {
            throw failure;
        }
        return Optional.ofNullable(runs.get(purge));
    }

    @Override
    public synchronized void record(PurgeRun run) {
        if (failure != null) {
            throw failure;
        }
        PurgeRun previous = runs.get(run.purge());
        Optional<java.time.Instant> success =
                run.lastSuccessAt().isPresent() || previous == null ? run.lastSuccessAt() : previous.lastSuccessAt();
        runs.put(
                run.purge(),
                new PurgeRun(run.purge(), run.lastRunAt(), run.lastResult(), success, run.lastCutoff(), run.deleted()));
    }
}
