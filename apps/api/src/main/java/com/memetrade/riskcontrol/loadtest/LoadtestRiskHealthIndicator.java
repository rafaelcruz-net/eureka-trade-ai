package com.memetrade.riskcontrol.loadtest;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * EXISTS ONLY WITH THE "loadtest" PROFILE: the health component {@code loadtestRisk}, read by
 * {@code scripts/load/load-test.mjs} to know when the preparation of the scenario {@code u6} is over and when the
 * cycles started. {@code UP} only while the cycles run; every other state is {@code UNKNOWN}, so the preparation never
 * changes the overall health nor the readiness of the API. Numbers, instants and state only.
 */
@Component
@Profile("loadtest")
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class LoadtestRiskHealthIndicator implements HealthIndicator {

    private final LoadtestRiskCycleDriver driver;
    private final LoadtestRiskPurgeTrigger purge;

    public LoadtestRiskHealthIndicator(LoadtestRiskCycleDriver driver, LoadtestRiskPurgeTrigger purge) {
        this.driver = driver;
        this.purge = purge;
    }

    @Override
    public Health health() {
        Health.Builder builder =
                driver.state() == LoadtestRiskCycleDriver.State.RUNNING ? Health.up() : Health.unknown();
        builder.withDetail("state", driver.state().name())
                .withDetail("rejectionsSeeded", driver.rejectionsSeeded())
                .withDetail("oldApprovedSeeded", driver.oldApprovedSeeded())
                .withDetail("evaluations", driver.evaluations())
                .withDetail("approved", driver.approved())
                .withDetail("rejected", driver.rejected())
                .withDetail("skipped", driver.skipped())
                .withDetail("cycleFailures", driver.failures())
                .withDetail("purgeAtSeconds", driver.purgeAtSeconds())
                // Loop 3 (F12): the time of every evaluate call by window, in microseconds, for the p95 of the script.
                .withDetail("evaluateMicros", driver.evaluateMicros());
        driver.cyclesStartedAt().ifPresent(at -> builder.withDetail("cyclesStartedAt", at.toString()));
        purge.last().ifPresent(round -> builder.withDetail("purgeRan", round.ran())
                .withDetail("purgeResult", round.result())
                .withDetail("purgeDeleted", round.deleted())
                .withDetail("purgeBatches", round.batches()));
        if (driver.state() == LoadtestRiskCycleDriver.State.FAILED) {
            builder.withDetail("failure", driver.failure());
        }
        return builder.build();
    }
}
