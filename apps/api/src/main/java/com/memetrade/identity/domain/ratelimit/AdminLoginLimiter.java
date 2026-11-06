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
 * Limits of the administrator entry (Q4 of the NFR Design, R-05), in the API process memory with its own counters (never
 * shared with the guest login): a 1-minute sliding window of at most {@code perIp} challenge and verify requests per IP,
 * and of at most {@code perAddress} per (address, IP) pair. Counting the address WITH the IP keeps a third party that
 * only knows the administrator address from locking the administrator out from another IP. Counters are lost on restart
 * (single-instance beta), like the guest limiter.
 */
public class AdminLoginLimiter {

    public static final Duration WINDOW = Duration.ofMinutes(1);

    private final int perIp;
    private final int perAddress;
    private final Clock clock;
    private final Map<String, Deque<Instant>> windows = new ConcurrentHashMap<>();

    public AdminLoginLimiter(int perIp, int perAddress, Clock clock) {
        if (perIp < 1 || perAddress < 1) {
            throw new IllegalArgumentException("Administrator entry limits must be positive");
        }
        this.perIp = perIp;
        this.perAddress = perAddress;
        this.clock = clock;
    }

    /**
     * Counts one request of {@code ip} for {@code address} (null when the address is unknown); returns the wait when
     * either limit is passed.
     */
    public Optional<Duration> acquire(String ip, String address) {
        Instant now = clock.instant();
        Optional<Duration> byIp = count("ip:" + ip, perIp, now);
        Optional<Duration> byPair =
                address == null ? Optional.empty() : count("pair:" + address + "|" + ip, perAddress, now);
        if (byIp.isPresent() && byPair.isPresent()) {
            return byIp.get().compareTo(byPair.get()) >= 0 ? byIp : byPair;
        }
        return byIp.isPresent() ? byIp : byPair;
    }

    private Optional<Duration> count(String key, int limit, Instant now) {
        Deque<Instant> window = windows.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (window) {
            prune(window, now);
            window.addLast(now);
            if (window.size() > limit) {
                Duration wait = Duration.between(now, window.peekFirst().plus(WINDOW));
                return Optional.of(wait.isNegative() || wait.isZero() ? Duration.ofSeconds(1) : wait);
            }
            return Optional.empty();
        }
    }

    /** Drops windows entirely in the past (run by the limiter eviction job every minute). */
    public void evictExpired() {
        Instant now = clock.instant();
        windows.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                prune(entry.getValue(), now);
                return entry.getValue().isEmpty();
            }
        });
    }

    public int trackedKeys() {
        return windows.size();
    }

    /** Clears every counter (between integration tests that share the process). */
    public void clear() {
        windows.clear();
    }

    private static void prune(Deque<Instant> window, Instant now) {
        Instant cutoff = now.minus(WINDOW);
        while (!window.isEmpty() && !window.peekFirst().isAfter(cutoff)) {
            window.removeFirst();
        }
    }
}
