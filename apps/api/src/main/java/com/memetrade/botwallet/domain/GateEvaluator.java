package com.memetrade.botwallet.domain;

import java.util.Optional;

/**
 * 261113-trading-real-bot U5 (BR1.4, logical-components.md GateEvaluator): the pure gates of a release whose quarantine
 * passed. Every gate green: ACTIVE. Otherwise BLOCKED with the FIRST red gate in this order: NETWORK_MISMATCH,
 * KILL_SWITCH_ON, DIVERGENCE_OPEN, CI_UNREACHABLE (the CI did not answer), CI_RED (the CI answered without success). A
 * CI that failed to answer never releases (U5-NFR4.3).
 */
public final class GateEvaluator {

    private GateEvaluator() {}

    public static GateOutcome evaluate(GateInputs in) {
        boolean networkMatches = in.releaseNetwork() == in.environmentNetwork();
        boolean killSwitchOff = !in.killSwitchOn();
        boolean noOpenDivergence = !in.divergenceOpen();
        boolean ciGreen = in.ci().isGreen();
        GateSnapshot snapshot = new GateSnapshot(
                ciGreen,
                ciGreen ? in.ci().runRef().orElse(null) : null,
                noOpenDivergence,
                killSwitchOff,
                networkMatches,
                in.ci().status());
        Optional<BlockedReason> red = firstRed(networkMatches, killSwitchOff, noOpenDivergence, in.ci());
        return new GateOutcome(red.isEmpty() ? ReleaseState.ACTIVE : ReleaseState.BLOCKED, red, snapshot);
    }

    private static Optional<BlockedReason> firstRed(
            boolean networkMatches, boolean killSwitchOff, boolean noOpenDivergence, CiResult ci) {
        if (!networkMatches) {
            return Optional.of(BlockedReason.NETWORK_MISMATCH);
        }
        if (!killSwitchOff) {
            return Optional.of(BlockedReason.KILL_SWITCH_ON);
        }
        if (!noOpenDivergence) {
            return Optional.of(BlockedReason.DIVERGENCE_OPEN);
        }
        return switch (ci.status()) {
            case GREEN -> Optional.empty();
            case UNREACHABLE -> Optional.of(BlockedReason.CI_UNREACHABLE);
            case RED -> Optional.of(BlockedReason.CI_RED);
        };
    }
}
