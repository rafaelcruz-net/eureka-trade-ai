package com.memetrade.ledger.loadtest;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * EXISTS ONLY WITH THE "loadtest" PROFILE: the health component {@code loadtestLedger}, read by
 * {@code scripts/load/load-test.mjs} to know when the preparation of the load ledger is over. {@code UP} only while
 * the cycles run; every other state is {@code UNKNOWN} (the last in the status order), so the preparation never
 * changes the overall health nor the readiness of the API. Numbers and state only.
 */
@Component
@Profile("loadtest")
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class LoadtestLedgerHealthIndicator implements HealthIndicator {

    private final LoadtestCycleDriver driver;

    public LoadtestLedgerHealthIndicator(LoadtestCycleDriver driver) {
        this.driver = driver;
    }

    @Override
    public Health health() {
        Health.Builder builder = driver.state() == LoadtestCycleDriver.State.RUNNING ? Health.up() : Health.unknown();
        builder.withDetail("state", driver.state().name())
                .withDetail("peopleSeen", driver.peopleSeen())
                .withDetail("peopleExpected", driver.expectedPeople())
                .withDetail("bots", driver.bots())
                .withDetail("fillsRecorded", driver.fillsRecorded())
                .withDetail("fillsExpected", driver.expectedFills())
                .withDetail("cycles", driver.cycles())
                .withDetail("cycleFailures", driver.cycleFailures())
                .withDetail("closeBots", driver.closeBots())
                .withDetail("closes", driver.closes())
                .withDetail("closeFailures", driver.closeFailures());
        if (driver.state() == LoadtestCycleDriver.State.FAILED) {
            builder.withDetail("failure", driver.failure());
        }
        return builder.build();
    }
}
