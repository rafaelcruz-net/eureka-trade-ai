package com.memetrade.realexits.fiscal;

import com.memetrade.shared.web.PersonRouteRateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * BR7.9, U7-NFR3.9: at most 10 fiscal exports per person per hour, with the limiter per person of the api (window and
 * limit as parameters since the U7). The idle keys are dropped on every request (no scheduled job of its own: the keys
 * are at most one per person of the pilot).
 */
@Component
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class FiscalExportLimiter {

    static final int LIMIT = 10;
    static final Duration WINDOW = Duration.ofHours(1);
    static final String ROUTE = "fiscal-export";

    private final PersonRouteRateLimiter limiter;

    public FiscalExportLimiter(Clock clock) {
        this.limiter = new PersonRouteRateLimiter(LIMIT, WINDOW, clock, PersonRouteRateLimiter.MAX_KEYS);
    }

    /** @return empty when accepted; otherwise the wait until the oldest export of the person leaves the hour */
    public Optional<Duration> acquire(UUID userId) {
        limiter.evictIdle();
        return limiter.acquire(userId.toString(), ROUTE);
    }
}
