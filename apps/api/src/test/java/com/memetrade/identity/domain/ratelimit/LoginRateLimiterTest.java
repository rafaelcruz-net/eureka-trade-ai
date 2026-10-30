package com.memetrade.identity.domain.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.testsupport.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u1")
class LoginRateLimiterTest {

    private final MutableClock clock = new MutableClock();
    private final LoginRateLimiter limiter = new LoginRateLimiter(10, clock);

    @Test
    void theEleventhRequestOfAnIpInOneMinuteIsLimitedWithRetryAfter() {
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.acquireForIp("10.0.0.1"))
                    .as("request " + (i + 1))
                    .isEmpty();
        }
        clock.advance(Duration.ofSeconds(20));

        assertThat(limiter.acquireForIp("10.0.0.1")).contains(Duration.ofSeconds(40));
    }

    @Test
    void anotherIpIsNotAffected() {
        for (int i = 0; i < 11; i++) {
            limiter.acquireForIp("10.0.0.1");
        }
        assertThat(limiter.acquireForIp("10.0.0.2")).isEmpty();
    }

    @Test
    void theWindowExpiresAfterOneMinute() {
        for (int i = 0; i < 11; i++) {
            limiter.acquireForIp("10.0.0.1");
        }
        clock.advance(LoginRateLimiter.WINDOW);

        assertThat(limiter.acquireForIp("10.0.0.1")).isEmpty();
    }

    @Test
    void aThirdPartyCannotBlockTheWalletOwnerOnAnotherIp() {
        for (int i = 0; i < 11; i++) {
            limiter.recordRejectedVerification("wallet-a", "6.6.6." + (i % 3));
        }

        assertThat(limiter.walletBlockedFor("wallet-a", "6.6.6.1")).isPresent();
        assertThat(limiter.walletBlockedFor("wallet-a", "192.168.0.10"))
                .as("owner's own IP")
                .isEmpty();
        assertThat(limiter.walletBlockedFor("wallet-b", "6.6.6.1")).isEmpty();
    }

    @Test
    void tenRefusedVerificationsAreStillAllowedAndTheyAgeOut() {
        for (int i = 0; i < 10; i++) {
            limiter.recordRejectedVerification("wallet-a", "6.6.6.6");
        }
        assertThat(limiter.walletBlockedFor("wallet-a", "6.6.6.6")).isEmpty();

        limiter.recordRejectedVerification("wallet-a", "6.6.6.6");
        assertThat(limiter.walletBlockedFor("wallet-a", "6.6.6.6")).isPresent();

        clock.advance(LoginRateLimiter.WINDOW);
        assertThat(limiter.walletBlockedFor("wallet-a", "6.6.6.6")).isEmpty();
    }

    @Test
    void challengesNeverCountTowardsTheWalletLimit() {
        for (int i = 0; i < 50; i++) {
            limiter.acquireForIp("10.0.0." + i);
        }
        assertThat(limiter.walletBlockedFor("any-wallet", "10.0.0.1")).isEmpty();
    }

    @Test
    void expiredWindowsAreEvicted() {
        limiter.acquireForIp("10.0.0.1");
        limiter.recordRejectedVerification("wallet-a", "10.0.0.1");
        assertThat(limiter.trackedKeys()).isEqualTo(2);

        clock.advance(LoginRateLimiter.WINDOW.plusSeconds(1));
        limiter.evictExpired();

        assertThat(limiter.trackedKeys()).isZero();
    }

    @Test
    void aNonPositiveLimitIsRefused() {
        assertThatThrownBy(() -> new LoginRateLimiter(0, clock)).isInstanceOf(IllegalArgumentException.class);
        assertThat(limiter.limit()).isEqualTo(10);
    }
}
