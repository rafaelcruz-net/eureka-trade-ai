package com.memetrade.pnl.web;

import com.memetrade.shared.web.PersonRouteRateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The own limiter of "check now" (NFR6.5, security-design.md): a dedicated {@link PersonRouteRateLimiter} with the
 * fixed limit {@code memetrade.ledger.reconciliation-run-limit-per-minute} (6) per person in 60 s. A wrapper type, so
 * the beans that inject the scanner limiter by type are not affected. In process memory: one instance in the beta.
 */
@Component
public class ReconciliationRunLimiter {

    static final String ROUTE = "reconciliation-run";

    private final PersonRouteRateLimiter limiter;

    public ReconciliationRunLimiter(
            @Value("${memetrade.ledger.reconciliation-run-limit-per-minute}") int limit, Clock clock) {
        this.limiter = new PersonRouteRateLimiter(limit, clock);
    }

    /** Empty when accepted; otherwise the wait for the {@code Retry-After} header. */
    public Optional<Duration> acquire(UUID person) {
        return limiter.acquire(person.toString(), ROUTE);
    }

    public void evictIdle() {
        limiter.evictIdle();
    }

    public int trackedKeys() {
        return limiter.trackedKeys();
    }

    public int limit() {
        return limiter.limit();
    }

    /** Clears every counter (integration tests that share the process). */
    public void clear() {
        limiter.clear();
    }
}
