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
 * Login attempt limits kept in the API process memory (BR1.11, NFR6.5): a 1-minute sliding window of at most
 * {@code limit} challenge + verification requests per IP; per wallet, only REFUSED verifications count, and only the
 * IPs that made them are blocked, so a third party cannot lock the wallet owner out. Challenges never count per
 * wallet. Counters are lost on restart (accepted for the single-instance beta; Redis before a second instance).
 */
public class LoginRateLimiter {

    public static final Duration WINDOW = Duration.ofMinutes(1);

    private final int limit;
    private final Clock clock;
    private final Map<String, Deque<Instant>> requestsByIp = new ConcurrentHashMap<>();
    private final Map<String, Deque<Rejection>> rejectionsByWallet = new ConcurrentHashMap<>();

    public LoginRateLimiter(int limit, Clock clock) {
        if (limit < 1) {
            throw new IllegalArgumentException("Login rate limit must be positive");
        }
        this.limit = limit;
        this.clock = clock;
    }

    public int limit() {
        return limit;
    }

    /** Counts a challenge or verification request of the IP; returns the wait when the IP is over the limit. */
    public Optional<Duration> acquireForIp(String ip) {
        Instant now = clock.instant();
        Deque<Instant> window = requestsByIp.computeIfAbsent(ip, key -> new ArrayDeque<>());
        synchronized (window) {
            prune(window, now);
            window.addLast(now);
            if (window.size() > limit) {
                return Optional.of(retryAfter(window.peekFirst(), now));
            }
            return Optional.empty();
        }
    }

    /** Whether verifications of this wallet are blocked FOR THIS IP (it took part in the refusals). */
    public Optional<Duration> walletBlockedFor(String wallet, String ip) {
        Deque<Rejection> rejections = rejectionsByWallet.get(wallet);
        if (rejections == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        synchronized (rejections) {
            rejections.removeIf(r -> !r.at().isAfter(now.minus(WINDOW)));
            boolean overLimit = rejections.size() > limit;
            boolean ipTookPart = rejections.stream().anyMatch(r -> r.ip().equals(ip));
            if (overLimit && ipTookPart) {
                return Optional.of(retryAfter(rejections.peekFirst().at(), now));
            }
            return Optional.empty();
        }
    }

    public void recordRejectedVerification(String wallet, String ip) {
        Deque<Rejection> rejections = rejectionsByWallet.computeIfAbsent(wallet, key -> new ArrayDeque<>());
        synchronized (rejections) {
            rejections.addLast(new Rejection(clock.instant(), ip));
        }
    }

    /** Drops windows that are entirely in the past, so memory does not grow (NFR3.5). */
    public void evictExpired() {
        Instant now = clock.instant();
        requestsByIp.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                prune(entry.getValue(), now);
                return entry.getValue().isEmpty();
            }
        });
        rejectionsByWallet.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                entry.getValue().removeIf(r -> !r.at().isAfter(now.minus(WINDOW)));
                return entry.getValue().isEmpty();
            }
        });
    }

    /** Number of tracked keys (IPs plus wallets), for tests of the eviction. */
    public int trackedKeys() {
        return requestsByIp.size() + rejectionsByWallet.size();
    }

    /** Clears every counter (used between integration tests that share the process). */
    public void clear() {
        requestsByIp.clear();
        rejectionsByWallet.clear();
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

    private record Rejection(Instant at, String ip) {}
}
