package com.memetrade.bots.worker;

import java.util.Optional;
import java.util.UUID;

/**
 * What one cycle did (BR3.2 to BR3.5): COMPLETED with its outcome, SKIPPED with its reason, FAILED with its cause, or
 * ALREADY_PROCESSED when the slot of the bot already had a cycle (nothing written).
 */
public record CycleResult(
        Kind kind,
        Optional<UUID> cycleId,
        Optional<String> action,
        Optional<String> outcome,
        Optional<String> skipReason,
        Optional<String> failureCause) {

    public enum Kind {
        COMPLETED,
        SKIPPED,
        FAILED,
        ALREADY_PROCESSED
    }

    static CycleResult completed(UUID cycleId, String action, String outcome) {
        return new CycleResult(
                Kind.COMPLETED,
                Optional.of(cycleId),
                Optional.of(action),
                Optional.of(outcome),
                Optional.empty(),
                Optional.empty());
    }

    static CycleResult skipped(UUID cycleId, String reason) {
        return new CycleResult(
                Kind.SKIPPED,
                Optional.of(cycleId),
                Optional.empty(),
                Optional.empty(),
                Optional.of(reason),
                Optional.empty());
    }

    static CycleResult failed(UUID cycleId, String cause) {
        return new CycleResult(
                Kind.FAILED,
                Optional.of(cycleId),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(cause));
    }

    static CycleResult alreadyProcessed() {
        return new CycleResult(
                Kind.ALREADY_PROCESSED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }
}
