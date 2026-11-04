package com.memetrade.identity.domain.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limit of the invitation lookup without a session (BR2.2, NFR6.6, NFR2.3): a 1-minute sliding window of at most
 * {@code limit} lookups per IP, with counters SEPARATE from the login limiter. Every lookup counts, malformed codes
 * included, and the decision comes before the invitation is searched, so a 429 never tells whether a code exists.
 * Counters live in the api process memory and restart at zero (accepted for the single-instance beta).
 */
public class InviteLookupRateLimiter {

    public static final Duration WINDOW = Duration.ofMinutes(1);

    private final int limit;
    private final Clock clock;
    private final Map<String, Deque<Instant>> lookupsByIp = new ConcurrentHashMap<>();

    public InviteLookupRateLimiter(int limit, Clock clock) {
        if (limit < 1) {
            throw new IllegalArgumentException("Invitation lookup rate limit must be positive");
        }
        this.limit = limit;
        this.clock = clock;
    }

    public int limit() {
        return limit;
    }

    /**
     * Counts one lookup of the IP; returns the wait (for {@code Retry-After}) when the IP already used its limit in the
     * window. A refused lookup is not added to the window, so the lookup made after {@code Retry-After} passes
     * (NFR6.6).
     */
    public Optional<Duration> acquire(String ip) {
        Instant now = clock.instant();
        Deque<Instant> window = lookupsByIp.computeIfAbsent(ip, key -> new ArrayDeque<>());
        synchronized (window) {
            prune(window, now);
            if (window.size() >= limit) {
                return Optional.of(retryAfter(window.peekFirst(), now));
            }
            window.addLast(now);
            return Optional.empty();
        }
    }

    /** Drops the IPs without a lookup inside the window, so keys created by an attacker do not pile up. */
    public void evictExpired() {
        Instant now = clock.instant();
        lookupsByIp.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                prune(entry.getValue(), now);
                return entry.getValue().isEmpty();
            }
        });
    }

    /** Number of tracked IPs, for tests of the eviction. */
    public int trackedKeys() {
        return lookupsByIp.size();
    }

    /** Clears every counter (used between integration tests that share the process). */
    public void clear() {
        lookupsByIp.clear();
    }

    private static void prune(Deque<Instant> window, Instant now) {
        Instant cutoff = now.minus(WINDOW);
        while (!window.isEmpty() && !window.peekFirst().isAfter(cutoff)) {
            window.removeFirst();
        }
    }

    private static Duration retryAfter(Instant oldest, Instant now) {
        Duration wait = Duration.between(now, oldest.plus(WINDOW));
        return wait.isNegative() || wait.isZero() ? Duration.ofSeconds(1) : wait;
    }
}
