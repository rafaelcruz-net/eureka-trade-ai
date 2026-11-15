package com.memetrade.marketdata.infra;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * 261113-trading-real-bot U2 (BR1.6, NFR1.5): with the REAL switched on ({@code memetrade.real.enabled=true}) and
 * outside the {@code test} and {@code local} profiles, the api and the worker refuse to start when
 * {@code MARKET_SOURCES} names a simulated source (sim-a or sim-b). REAL never runs on simulated prices; there is no
 * automatic switch to a simulated source either (BR1.9). Present in both processes.
 */
@Component
public class MarketSourceGuard {

    static final String REAL_ENABLED = "memetrade.real.enabled";

    public MarketSourceGuard(MarketDataProperties properties, Environment environment) {
        check(
                properties,
                environment.getProperty(REAL_ENABLED, Boolean.class, Boolean.FALSE),
                environment.acceptsProfiles(Profiles.of("test", "local")));
    }

    /**
     * @throws IllegalStateException when REAL is on, the profile is neither test nor local and a simulated source is
     *     configured; the message names the settings, never a value of a secret
     */
    static void check(MarketDataProperties properties, boolean realEnabled, boolean testOrLocal) {
        if (realEnabled && !testOrLocal && properties.anySimulated()) {
            throw new IllegalStateException("MARKET_SOURCES must not contain sim-a or sim-b while"
                    + " MEMETRADE_REAL_ENABLED=true outside the test and local profiles: use MARKET_SOURCES=coingecko");
        }
    }
}
