package com.memetrade.botwallet.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 4 of the U5, written BEFORE the code (BR1.4, S3, S4, S29, U5-NFR4.3): the pure gates of a release after its
 * quarantine. All green turns it ACTIVE; any red one blocks it with the FIRST red gate in the fixed order
 * NETWORK_MISMATCH, KILL_SWITCH_ON, DIVERGENCE_OPEN, CI_UNREACHABLE, CI_RED; the snapshot keeps every gate.
 */
@Tag("u5-real-operations")
class GateEvaluatorTest {

    private static final CiResult GREEN = CiResult.green("4242");

    private static GateInputs inputs(Network release, boolean killSwitch, boolean divergence, CiResult ci) {
        return new GateInputs(release, Network.DEVNET, killSwitch, divergence, ci);
    }

    @Test
    void everyGreenGateMakesTheReleaseActiveWithTheSnapshot() {
        GateOutcome outcome = GateEvaluator.evaluate(inputs(Network.DEVNET, false, false, GREEN));

        assertThat(outcome.state()).isEqualTo(ReleaseState.ACTIVE);
        assertThat(outcome.blockedReason()).isEmpty();
        assertThat(outcome.snapshot())
                .isEqualTo(new GateSnapshot(true, "4242", true, true, true, CiResult.Status.GREEN));
    }

    @Test
    void aRedCiBlocksCiRed() {
        GateOutcome outcome = GateEvaluator.evaluate(inputs(Network.DEVNET, false, false, CiResult.red()));

        assertThat(outcome.state()).isEqualTo(ReleaseState.BLOCKED);
        assertThat(outcome.blockedReason()).contains(BlockedReason.CI_RED);
        assertThat(outcome.snapshot().ciGreen()).isFalse();
    }

    @Test
    void aCiThatDidNotAnswerBlocksCiUnreachableNeverReleases() {
        GateOutcome outcome = GateEvaluator.evaluate(inputs(Network.DEVNET, false, false, CiResult.unreachable()));

        assertThat(outcome.blockedReason()).contains(BlockedReason.CI_UNREACHABLE);
    }

    @Test
    void anOpenDivergenceBlocksBeforeTheCi() {
        GateOutcome outcome = GateEvaluator.evaluate(inputs(Network.DEVNET, false, true, CiResult.unreachable()));

        assertThat(outcome.blockedReason()).contains(BlockedReason.DIVERGENCE_OPEN);
        assertThat(outcome.snapshot().noOpenDivergence()).isFalse();
    }

    @Test
    void theKillSwitchBlocksBeforeTheDivergence() {
        GateOutcome outcome = GateEvaluator.evaluate(inputs(Network.DEVNET, true, true, CiResult.red()));

        assertThat(outcome.blockedReason()).contains(BlockedReason.KILL_SWITCH_ON);
        assertThat(outcome.snapshot().killSwitchOff()).isFalse();
    }

    @Test
    void anotherNetworkBlocksFirstOfAll() {
        GateOutcome outcome = GateEvaluator.evaluate(inputs(Network.MAINNET, true, true, CiResult.red()));

        assertThat(outcome.blockedReason()).contains(BlockedReason.NETWORK_MISMATCH);
        assertThat(outcome.snapshot().networkMatches()).isFalse();
        assertThat(outcome.snapshot())
                .isEqualTo(new GateSnapshot(false, null, false, false, false, CiResult.Status.RED));
    }

    @Test
    void theSnapshotBecomesTheDetailsOfTheAuditEventWithoutAnyAddress() {
        GateOutcome outcome = GateEvaluator.evaluate(inputs(Network.DEVNET, false, true, GREEN));

        assertThat(outcome.snapshot().details())
                .containsEntry("ciGreen", "true")
                .containsEntry("ciRunRef", "4242")
                .containsEntry("noOpenDivergence", "false")
                .containsEntry("killSwitchOff", "true")
                .containsEntry("networkMatches", "true")
                .containsEntry("ci", "GREEN");
    }

    @Test
    void theCiResultsAreGreenOnlyWithASuccessfulRun() {
        assertThat(CiResult.green("1").isGreen()).isTrue();
        assertThat(CiResult.red().isGreen()).isFalse();
        assertThat(CiResult.unreachable().runRef()).isEqualTo(Optional.empty());
        assertThat(CiResult.green("77").runRef()).contains("77");
    }
}
