package com.memetrade.shared.config;

import java.util.Arrays;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start the API with the {@code loadtest} profile unless {@code test} is also active
 * (infrastructure-specification.md, "Proteções"): the raised login limit never reaches a normal run.
 */
@Component
@Profile("loadtest")
public class LoadtestProfileGuard {

    public LoadtestProfileGuard(Environment environment) {
        verify(environment.getActiveProfiles());
    }

    static void verify(String[] activeProfiles) {
        boolean hasLoadtest = Arrays.asList(activeProfiles).contains("loadtest");
        boolean hasTest = Arrays.asList(activeProfiles).contains("test");
        if (hasLoadtest && !hasTest) {
            throw new IllegalStateException("The 'loadtest' profile requires the 'test' profile to be active as well");
        }
    }
}
