package com.memetrade.marketdata.infra;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Collection cadence (15 s, performance-design.md Q4) and the seed of the simulated source. */
@ConfigurationProperties(prefix = "memetrade.market")
public record MarketDataProperties(Duration collectInterval, Simulated simulated) {

    public record Simulated(long seed, int tokenCount) {}
}
