package com.memetrade.shared.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Per person and per route request limit (security-design.md, NFR6.3, NFR2.8): a sliding window (60 s by default; a
 * parameter since the U7 of 261113-trading-real-bot) kept as the
 * instants of the accepted requests per key ({@code person|route}), at most {@code limit} of them. The key is the
 * person of the authenticated principal and the route name, never a wallet nor an IP. Keys without a request for more
 * than 2 minutes are dropped by {@link #evictIdle()}; above {@value #MAX_KEYS} keys the least recently used goes first.
 * In process memory: a risk accepted for the single-instance beta, revisited with the hosting.
 */
public class PersonRouteRateLimiter {

    public static final Duration WINDOW = Duration.ofSeconds(60);
    public static final Duration IDLE = Duration.ofMinutes(2);
    public static final int MAX_KEYS = 10_000;

    private final int limit;
    private final Duration window;
    private final Duration idle;
    private final Clock clock;
    private final int maxKeys;
    private final Map<String, Window> windows = new LinkedHashMap<>(256, 0.75f, true);

    public PersonRouteRateLimiter(int limit, Clock clock) {
        this(limit, clock, MAX_KEYS);
    }

    public PersonRouteRateLimiter(int limit, Clock clock, int maxKeys) {
        this(limit, WINDOW, clock, maxKeys);
    }

    /**
     * 261113-trading-real-bot U7 (logical-components.md "Limite da exportação"): the window is a parameter (the fiscal
     * export allows 10 per hour); the idle keys are evicted after twice the window of this limiter.
     */
    public PersonRouteRateLimiter(int limit, Duration window, Clock clock, int maxKeys) {
        if (limit < 1 || maxKeys < 1 || window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("The limit, the window and the key cap must be positive");
        }
        this.limit = limit;
        this.window = window;
        this.idle = window.multipliedBy(2);
        this.clock = clock;
        this.maxKeys = maxKeys;
    }

    /**
     * Counts a request of {@code person} on {@code route}.
     *
     * @return empty when accepted; otherwise the wait until the oldest request leaves the window (at least 1 s)
     */
    public synchronized Optional<Duration> acquire(String person, String route) {
        Instant now = clock.instant();
        String key = person + "|" + route;
        Window counts = windows.computeIfAbsent(key, k -> new Window());
        counts.prune(now, window);
        counts.lastSeen = now;
        if (counts.requests.size() >= limit) {
            return Optional.of(retryAfter(counts.requests.peekFirst(), now, window));
        }
        counts.requests.addLast(now);
        while (windows.size() > maxKeys) {
            Iterator<String> eldest = windows.keySet().iterator();
            eldest.next();
            eldest.remove();
        }
        return Optional.empty();
    }

    /** Drops the keys without a request for more than twice the window (2 minutes by default; run every minute). */
    public synchronized void evictIdle() {
        Instant cutoff = clock.instant().minus(idle);
        windows.values().removeIf(window -> window.lastSeen.isBefore(cutoff));
    }

    public synchronized int trackedKeys() {
        return windows.size();
    }

    /** Clears every counter (integration tests that share the process). */
    public synchronized void clear() {
        windows.clear();
    }

    public int limit() {
        return limit;
    }

    public Duration window() {
        return window;
    }

    /** Whole seconds, rounded up, until {@code oldest} leaves the window; at least 1. */
    static Duration retryAfter(Instant oldest, Instant now) {
        return retryAfter(oldest, now, WINDOW);
    }

    static Duration retryAfter(Instant oldest, Instant now, Duration window) {
        Duration wait = Duration.between(now, oldest.plus(window));
        long seconds = (wait.toMillis() + 999) / 1000;
        return Duration.ofSeconds(Math.max(1, seconds));
    }

    private static final class Window {
        private final Deque<Instant> requests = new ArrayDeque<>();
        private Instant lastSeen;

        void prune(Instant now, Duration window) {
            Instant cutoff = now.minus(window);
            while (!requests.isEmpty() && !requests.peekFirst().isAfter(cutoff)) {
                requests.removeFirst();
            }
        }
    }
}
