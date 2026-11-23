package com.memetrade.pnl.loadtest;

import com.memetrade.shared.config.ProcessRole;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * EXISTS ONLY WITH THE "loadtest" PROFILE: the health component {@code loadtestPnl} the scenario {@code pnl-by-mode}
 * waits for (UP once every person is seeded, and UP with the state OFF in any other scenario; DOWN with the state and
 * the progress while waiting or seeding, or FAILED with the class of the failure). Never in the readiness group.
 */
@Component("loadtestPnl")
@Profile("loadtest")
@ProcessRole(ProcessRole.Kind.API)
public class LoadtestPnlHealthIndicator implements HealthIndicator {

    private final LoadtestPnlSeed seed;

    public LoadtestPnlHealthIndicator(LoadtestPnlSeed seed) {
        this.seed = seed;
    }

    @Override
    public Health health() {
        // OFF (no people asked, another scenario) never turns the health of the api DOWN.
        Health.Builder builder = seed.state() == LoadtestPnlSeed.State.UP || seed.state() == LoadtestPnlSeed.State.OFF
                ? Health.up()
                : Health.down();
        return builder.withDetail("state", seed.state().name())
                .withDetail("peopleSeeded", seed.seeded())
                .withDetail("peopleExpected", seed.expected())
                .withDetail("failure", seed.failure())
                .build();
    }
}
