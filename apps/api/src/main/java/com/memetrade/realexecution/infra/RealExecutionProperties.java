package com.memetrade.realexecution.infra;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the REAL execution under {@code memetrade.real.execution} (application.yml, U4-NFR3.3, U4-NFR1.5,
 * BR3.2, BR4.2, BR5.1, BR9.1). Fixed values; only the base URL of the Jupiter comes from the environment, and outside
 * the profiles {@code test} and {@code local} it must be one of the fixed hosts ({@link RealExecutionSettings}).
 */
@ConfigurationProperties(prefix = "memetrade.real.execution")
public record RealExecutionProperties(
        Jupiter jupiter,
        Duration quoteMaxAge,
        Duration signingWindow,
        Duration claimStuckAfter,
        Duration confirmationTimeout,
        Duration lease,
        long maxPriorityFeeLamports,
        int decisionBatchSize,
        int orderBatchSize,
        int upstreamFailuresToPause,
        Duration upstreamHealthyFor,
        Jobs jobs) {

    /** U4-NFR3.3, U4-NFR1.5: 2 s per call; {@code maxAccounts} of the route, and the smaller one of the one retry. */
    public record Jupiter(String baseUrl, Duration timeout, int maxAccounts, int retryMaxAccounts) {}

    /**
     * Intervals of the seven jobs of the worker (logical-components.md). The gauges of the ambiguous and of the oldest
     * SENT order are refreshed by the ambiguous job, every 15 s, so the worker keeps 19 scheduled methods.
     */
    public record Jobs(
            Duration queueInterval,
            Duration driverInterval,
            Duration confirmationInterval,
            Duration ambiguousInterval,
            Duration signingDeadlineInterval,
            Duration healthInterval,
            Duration liquidationInterval) {}
}
