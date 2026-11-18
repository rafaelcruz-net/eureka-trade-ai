package com.memetrade.txsigner.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * NFR1.9 (second half, Step 31 of the U1): at most {@value #REQUESTS_PER_MINUTE} signing requests per minute per bot
 * wallet between the api (or the worker) and the TxSigner. A sliding window on the injectable clock (team.md
 * "Determinismo"): only an admitted request is counted, and a slot is freed exactly when the oldest admitted request
 * leaves the window. A wallet without a request in the last window is forgotten, at most once per window, so the memory
 * follows the active wallets. In process memory: the TxSigner runs as a single task in the beta, and a restart only
 * opens the window again, which never weakens the content policy that still checks every request.
 */
public final class WalletRequestLimiter {

    /** NFR1.9: the limit per bot wallet in one minute. */
    public static final int REQUESTS_PER_MINUTE = 60;

    private final int limit;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> admitted = new HashMap<>();
    private Instant lastEviction;

    public WalletRequestLimiter(int limit, Duration window, Clock clock) {
        if (limit < 1) {
            throw new IllegalArgumentException("The limit per wallet must be at least 1");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("The window of the limit per wallet must be positive");
        }
        this.limit = limit;
        this.window = window;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lastEviction = clock.instant();
    }

    /** The limit of NFR1.9: {@value #REQUESTS_PER_MINUTE} per minute per wallet. */
    public static WalletRequestLimiter perMinute(Clock clock) {
        return new WalletRequestLimiter(REQUESTS_PER_MINUTE, Duration.ofMinutes(1), clock);
    }

    /** @return true and counts the request when the wallet is under its limit; false, counting nothing, otherwise */
    public synchronized boolean tryAcquire(String botWalletAddress) {
        Instant now = clock.instant();
        Instant oldestKept = now.minus(window);
        evictIdle(now, oldestKept);
        Deque<Instant> requests = admitted.computeIfAbsent(String.valueOf(botWalletAddress), any -> new ArrayDeque<>());
        dropExpired(requests, oldestKept);
        if (requests.size() >= limit) {
            return false;
        }
        requests.addLast(now);
        return true;
    }

    /** The wallets kept in memory (for the tests of the eviction). */
    synchronized int trackedWallets() {
        return admitted.size();
    }

    private void evictIdle(Instant now, Instant oldestKept) {
        if (now.isBefore(lastEviction.plus(window))) {
            return;
        }
        lastEviction = now;
        admitted.values().removeIf(requests -> {
            dropExpired(requests, oldestKept);
            return requests.isEmpty();
        });
    }

    /** A request admitted at {@code oldestKept} or before has left the window. */
    private static void dropExpired(Deque<Instant> requests, Instant oldestKept) {
        while (!requests.isEmpty() && !requests.peekFirst().isAfter(oldestKept)) {
            requests.removeFirst();
        }
    }
}
