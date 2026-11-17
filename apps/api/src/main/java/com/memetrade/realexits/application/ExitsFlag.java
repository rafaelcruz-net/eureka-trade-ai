package com.memetrade.realexits.application;

import com.memetrade.botwallet.api.ExitsAvailability;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The {@link ExitsAvailability} of the M1 (BR8.1, BR9.3, U7-NFR4.10): {@code memetrade.real.exits.enabled}, off by
 * default in every profile (application.yml); only the local test run turns it on, with the simulated adapter.
 */
@Component
public class ExitsFlag implements ExitsAvailability {

    static final String PROPERTY = "memetrade.real.exits.enabled";

    private final boolean enabled;

    public ExitsFlag(Environment environment) {
        this.enabled = environment.getProperty(PROPERTY, Boolean.class, false);
    }

    @Override
    public boolean exitsEnabled() {
        return enabled;
    }
}
