package com.memetrade.riskcontrol.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.riskcontrol.infra.RiskControlHealthIndicator.LastRun;
import com.memetrade.riskcontrol.infra.RiskControlHealthIndicator.Runs;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/** Passo 14 of U6 (NFR9.1, reliability-design.md): the rule of the {@code riskcontrol} health component. */
@Tag("u6")
class RiskControlHealthIndicatorTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");

    @Test
    void upWhenTheLastSuccessIsLessThan26HoursOld() {
        Instant success = NOW.minus(Duration.ofHours(25).plusMinutes(59));
        Health health = RiskControlHealthIndicator.evaluate(
                new Runs(Optional.of(new LastRun(success, "SUCCEEDED")), Optional.of(success)),
                NOW,
                NOW.minus(Duration.ofDays(5)));

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("lastSuccessAt", success.toString())
                .doesNotContainKey("reason");
    }

    @Test
    void upWithoutAnyRoundWhileTheProcessIsYoungerThan26Hours() {
        Health health = RiskControlHealthIndicator.evaluate(
                new Runs(Optional.empty(), Optional.empty()), NOW, NOW.minus(Duration.ofHours(25)));

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void downStaleWithoutAnyRoundAfter26HoursOfProcess() {
        Health health = RiskControlHealthIndicator.evaluate(
                new Runs(Optional.empty(), Optional.empty()), NOW, NOW.minus(Duration.ofHours(26)));

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("reason", "PURGE_STALE");
    }

    @Test
    void downStaleWhenTheLastSuccessIs26HoursOld() {
        Instant success = NOW.minus(Duration.ofHours(26));
        Health health = RiskControlHealthIndicator.evaluate(
                new Runs(Optional.of(new LastRun(success, "SUCCEEDED")), Optional.of(success)), NOW, NOW);

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("reason", "PURGE_STALE");
    }

    @Test
    void downFailedWhenTheLastRoundFailedAndNoRecentSuccess() {
        Instant failed = NOW.minus(Duration.ofHours(2));
        Health health = RiskControlHealthIndicator.evaluate(
                new Runs(Optional.of(new LastRun(failed, "FAILED")), Optional.of(NOW.minus(Duration.ofDays(3)))),
                NOW,
                NOW.minus(Duration.ofDays(10)));

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("reason", "PURGE_FAILED").containsEntry("lastResult", "FAILED");
    }

    @Test
    void aFailedRoundAfterARecentSuccessIsStillUp() {
        Instant success = NOW.minus(Duration.ofHours(20));
        Health health = RiskControlHealthIndicator.evaluate(
                new Runs(Optional.of(new LastRun(NOW.minus(Duration.ofHours(1)), "FAILED")), Optional.of(success)),
                NOW,
                NOW.minus(Duration.ofDays(10)));

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("lastResult", "FAILED");
    }

    @Test
    void theDetailsCarryOnlyInstantsAndClosedValues() {
        Instant failed = NOW.minus(Duration.ofHours(30));
        Health health = RiskControlHealthIndicator.evaluate(
                new Runs(Optional.of(new LastRun(failed, "FAILED")), Optional.empty()), NOW, NOW);

        assertThat(health.getDetails().keySet()).containsExactlyInAnyOrder("lastRunAt", "lastResult", "reason");
    }
}
