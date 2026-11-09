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
 * Limit of the public legal documents (security-design.md, NFR6.3): a 1-minute sliding window of at most
 * {@code limit} requests per client IP, with counters of its OWN (never shared with the login or invitation limiters).
 * The exemption of the internal networks is decided ONLY by the direct peer of the connection, never by the resolved
 * client IP nor by {@code X-Forwarded-For} (Q1 of the NFR Design), so a forged header never escapes the limit.
 * Counters live in the api process memory, per instance (scalability-design.md).
 */
public class LegalDocumentsRateLimiter {

    public static final Duration WINDOW = Duration.ofMinutes(1);

    private final int limit;
    private final Clock clock;
    private final InternalNetworks internalNetworks;
    private final Map<String, Deque<Instant>> requestsByIp = new ConcurrentHashMap<>();

    public LegalDocumentsRateLimiter(int limit, Clock clock, InternalNetworks internalNetworks) {
        if (limit < 1) {
            throw new IllegalArgumentException("Legal documents rate limit must be positive");
        }
        this.limit = limit;
        this.clock = clock;
        this.internalNetworks = internalNetworks;
    }

    public int limit() {
        return limit;
    }

    /**
     * Counts one request; returns the wait (for {@code Retry-After}) when the client IP already used its limit.
     *
     * @param clientIp the client IP resolved by the trusted-proxy rule, the key of the counter
     * @param directPeer {@code getRemoteAddr()}, the ONLY address compared with the internal networks
     */
    public Optional<Duration> acquire(String clientIp, String directPeer) {
        if (internalNetworks.contains(directPeer)) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Deque<Instant> window = requestsByIp.computeIfAbsent(clientIp, key -> new ArrayDeque<>());
        synchronized (window) {
            prune(window, now);
            if (window.size() >= limit) {
                return Optional.of(retryAfter(window.peekFirst(), now));
            }
            window.addLast(now);
            return Optional.empty();
        }
    }

    /** Drops the IPs without a request inside the window, so keys do not pile up (LoginLimiterEvictionJob). */
    public void evictExpired() {
        Instant now = clock.instant();
        requestsByIp.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                prune(entry.getValue(), now);
                return entry.getValue().isEmpty();
            }
        });
    }

    /** Number of tracked IPs, for tests of the eviction. */
    public int trackedKeys() {
        return requestsByIp.size();
    }

    /** Clears every counter (used between integration tests that share the process). */
    public void clear() {
        requestsByIp.clear();
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
