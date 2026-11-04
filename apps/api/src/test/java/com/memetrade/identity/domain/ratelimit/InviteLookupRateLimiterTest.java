package com.memetrade.identity.domain.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.testsupport.MutableClock;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Passo 8 (BR2.2, NFR6.6, NFR2.3): 10 lookups per IP per sliding minute, separate counters, eviction. */
@Tag("u3")
class InviteLookupRateLimiterTest {

    private final MutableClock clock = new MutableClock();
    private final InviteLookupRateLimiter limiter = new InviteLookupRateLimiter(10, clock);

    @Test
    void theTenthLookupPassesAndTheEleventhGetsTheWaitForRetryAfter() {
        for (int i = 1; i <= 10; i++) {
            assertThat(limiter.acquire("10.0.0.1")).as("lookup %d", i).isEmpty();
        }

        Optional<Duration> eleventh = limiter.acquire("10.0.0.1");

        assertThat(eleventh).contains(Duration.ofMinutes(1));
    }

    @Test
    void theWindowSlidesOverOneMinute() {
        for (int i = 0; i < 10; i++) {
            limiter.acquire("10.0.0.1");
            clock.advance(Duration.ofSeconds(5));
        }
        Duration retryAfter = limiter.acquire("10.0.0.1").orElseThrow();
        assertThat(retryAfter).isEqualTo(Duration.ofSeconds(10));
        assertThat(limiter.acquire("10.0.0.1")).as("still inside the window").isPresent();

        clock.advance(retryAfter);

        assertThat(limiter.acquire("10.0.0.1"))
                .as("after Retry-After the oldest lookup left the window")
                .isEmpty();
        assertThat(limiter.acquire("10.0.0.1")).as("the window is full again").isPresent();
    }

    @Test
    void eachIpHasItsOwnCounter() {
        for (int i = 0; i < 11; i++) {
            limiter.acquire("10.0.0.1");
        }

        assertThat(limiter.acquire("10.0.0.1")).isPresent();
        assertThat(limiter.acquire("10.0.0.2")).isEmpty();
    }

    @Test
    void theCounterIsSeparateFromTheLoginLimiter() {
        LoginRateLimiter login = new LoginRateLimiter(10, clock);
        for (int i = 0; i < 11; i++) {
            limiter.acquire("10.0.0.1");
        }

        assertThat(login.acquireForIp("10.0.0.1"))
                .as("lookups do not count for the login")
                .isEmpty();
        for (int i = 0; i < 10; i++) {
            login.acquireForIp("10.0.0.3");
        }
        assertThat(limiter.acquire("10.0.0.3"))
                .as("logins do not count for the lookup")
                .isEmpty();
    }

    @Test
    void evictionDropsOnlyIpsWithoutALookupInsideTheWindow() {
        limiter.acquire("10.0.0.1");
        clock.advance(Duration.ofSeconds(30));
        limiter.acquire("10.0.0.2");
        clock.advance(Duration.ofSeconds(31));

        limiter.evictExpired();

        assertThat(limiter.trackedKeys()).isEqualTo(1);
        clock.advance(Duration.ofMinutes(1));
        limiter.evictExpired();
        assertThat(limiter.trackedKeys()).isZero();
    }

    @Test
    void clearResetsEveryCounterAndTheLimitMustBePositive() {
        for (int i = 0; i < 11; i++) {
            limiter.acquire("10.0.0.1");
        }
        limiter.clear();

        assertThat(limiter.acquire("10.0.0.1")).isEmpty();
        assertThat(limiter.limit()).isEqualTo(10);
        assertThatThrownBy(() -> new InviteLookupRateLimiter(0, clock)).isInstanceOf(IllegalArgumentException.class);
    }
}
