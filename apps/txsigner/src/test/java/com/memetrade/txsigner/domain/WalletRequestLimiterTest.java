package com.memetrade.txsigner.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.txsigner.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Step 31 of the U1, written BEFORE the code (NFR1.9): a sliding window per bot wallet on an injectable clock. Only an
 * admitted request is counted; the window frees a slot exactly when the oldest admitted request leaves it.
 */
class WalletRequestLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-18T12:00:00Z"));

    @Test
    void theNfrLimitIsSixtyPerMinute() {
        WalletRequestLimiter limiter = WalletRequestLimiter.perMinute(clock);
        for (int i = 0; i < 60; i++) {
            assertThat(limiter.tryAcquire("A")).as("request " + (i + 1)).isTrue();
        }

        assertThat(limiter.tryAcquire("A")).isFalse();
        assertThat(limiter.tryAcquire("B")).isTrue();
    }

    @Test
    void aRefusedRequestIsNotCountedAndTheWindowSlides() {
        WalletRequestLimiter limiter = new WalletRequestLimiter(2, Duration.ofSeconds(10), clock);
        assertThat(limiter.tryAcquire("A")).isTrue();
        clock.advance(Duration.ofSeconds(4));
        assertThat(limiter.tryAcquire("A")).isTrue();
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("A")).isFalse();
        }

        clock.advance(Duration.ofSeconds(6));
        assertThat(limiter.tryAcquire("A"))
                .as("the first request left the window")
                .isTrue();
        assertThat(limiter.tryAcquire("A")).isFalse();
        clock.advance(Duration.ofSeconds(4));
        assertThat(limiter.tryAcquire("A"))
                .as("the second request left the window")
                .isTrue();
    }

    @Test
    void idleWalletsAreForgotten() {
        WalletRequestLimiter limiter = new WalletRequestLimiter(1, Duration.ofSeconds(10), clock);
        for (int i = 0; i < 100; i++) {
            limiter.tryAcquire("W" + i);
        }
        assertThat(limiter.trackedWallets()).isEqualTo(100);

        clock.advance(Duration.ofSeconds(10));
        limiter.tryAcquire("other");

        assertThat(limiter.trackedWallets()).isEqualTo(1);
    }

    @Test
    void anInvalidLimitOrWindowIsRefusedAtConstruction() {
        assertThatThrownBy(() -> new WalletRequestLimiter(0, Duration.ofMinutes(1), clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WalletRequestLimiter(1, Duration.ZERO, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
