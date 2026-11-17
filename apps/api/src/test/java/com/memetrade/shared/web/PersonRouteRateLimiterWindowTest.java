package com.memetrade.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.testsupport.MutableClock;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Step 9 of the U7 of 261113-trading-real-bot (logical-components.md "Limite da exportação", BR7.9, S32, U7-NFR3.9):
 * the limiter per person takes its window and its limit in the constructor. The existing uses keep the window of 60 s;
 * the export uses 10 per hour; the idle keys are evicted after twice the window of each limiter.
 */
@Tag("u7-exits-and-fiscal")
class PersonRouteRateLimiterWindowTest {

    private final MutableClock clock = new MutableClock();

    @Test
    void theEleventhRequestInTheHourIsRefusedUntilTheOldestLeavesTheWindow() {
        PersonRouteRateLimiter limiter = new PersonRouteRateLimiter(10, Duration.ofHours(1), clock, 100);
        for (int request = 0; request < 10; request++) {
            assertThat(limiter.acquire("person", "fiscal-export")).isEmpty();
            clock.advance(Duration.ofMinutes(1));
        }

        Optional<Duration> refused = limiter.acquire("person", "fiscal-export");

        assertThat(refused).contains(Duration.ofMinutes(50));
        clock.advance(Duration.ofMinutes(50));
        assertThat(limiter.acquire("person", "fiscal-export")).isEmpty();
    }

    @Test
    void anotherPersonHasItsOwnCount() {
        PersonRouteRateLimiter limiter = new PersonRouteRateLimiter(1, Duration.ofHours(1), clock, 100);

        assertThat(limiter.acquire("a", "fiscal-export")).isEmpty();
        assertThat(limiter.acquire("b", "fiscal-export")).isEmpty();
        assertThat(limiter.acquire("a", "fiscal-export")).isPresent();
    }

    @Test
    void theIdleKeysAreEvictedAfterTwiceTheWindowOfTheLimiter() {
        PersonRouteRateLimiter limiter = new PersonRouteRateLimiter(10, Duration.ofHours(1), clock, 100);
        limiter.acquire("person", "fiscal-export");

        clock.advance(Duration.ofMinutes(119));
        limiter.evictIdle();
        assertThat(limiter.trackedKeys()).isOne();
        clock.advance(Duration.ofMinutes(2));
        limiter.evictIdle();
        assertThat(limiter.trackedKeys()).isZero();
    }

    @Test
    void theExistingConstructorsKeepTheWindowOfSixtySeconds() {
        PersonRouteRateLimiter limiter = new PersonRouteRateLimiter(1, clock);

        assertThat(limiter.window()).isEqualTo(Duration.ofSeconds(60));
        limiter.acquire("p", "scanner");
        assertThat(limiter.acquire("p", "scanner")).contains(Duration.ofSeconds(60));
    }

    @Test
    void aWindowMustBePositive() {
        assertThatThrownBy(() -> new PersonRouteRateLimiter(1, Duration.ZERO, clock, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
