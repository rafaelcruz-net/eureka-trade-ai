package com.memetrade.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.testsupport.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Sliding window of 60 s per person and route (NFR6.3, NFR2.8), with the injectable clock. */
@Tag("u4")
class PersonRouteRateLimiterTest {

    private final MutableClock clock = new MutableClock();
    private final PersonRouteRateLimiter limiter = new PersonRouteRateLimiter(60, clock);

    @Test
    void theSixtiethRequestPassesAndTheSixtyFirstWaits() {
        for (int i = 0; i < 60; i++) {
            assertThat(limiter.acquire("p1", "scanner")).as("request %d", i + 1).isEmpty();
        }

        assertThat(limiter.acquire("p1", "scanner")).isPresent();
    }

    @Test
    void retryAfterIsTheWholeSecondsUntilTheOldestLeavesTheWindow() {
        limiter.acquire("p1", "scanner");
        clock.advance(Duration.ofMillis(20_500));
        for (int i = 0; i < 59; i++) {
            limiter.acquire("p1", "scanner");
        }

        assertThat(limiter.acquire("p1", "scanner")).contains(Duration.ofSeconds(40));
        clock.advance(Duration.ofMillis(39_500));
        assertThat(limiter.acquire("p1", "scanner"))
                .as("the first request left the window")
                .isEmpty();
    }

    @Test
    void routesAndPeopleAreCountedSeparately() {
        for (int i = 0; i < 60; i++) {
            limiter.acquire("p1", "scanner");
        }

        assertThat(limiter.acquire("p1", "assessment")).isEmpty();
        assertThat(limiter.acquire("p2", "scanner")).isEmpty();
        assertThat(limiter.acquire("p1", "scanner")).isPresent();
    }

    @Test
    void aRefusedRequestDoesNotExtendTheWait() {
        for (int i = 0; i < 60; i++) {
            limiter.acquire("p1", "scanner");
        }
        for (int i = 0; i < 10; i++) {
            limiter.acquire("p1", "scanner");
        }
        clock.advance(Duration.ofSeconds(60));

        assertThat(limiter.acquire("p1", "scanner")).isEmpty();
    }

    @Test
    void idleKeysAreDroppedAfterTwoMinutes() {
        limiter.acquire("p1", "scanner");
        clock.advance(Duration.ofMinutes(1));
        limiter.acquire("p2", "scanner");
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));

        limiter.evictIdle();

        assertThat(limiter.trackedKeys()).isEqualTo(1);
    }

    @Test
    void theNumberOfKeysIsCappedDroppingTheLeastRecentlyUsed() {
        PersonRouteRateLimiter small = new PersonRouteRateLimiter(1, clock, 3);
        small.acquire("p1", "scanner");
        small.acquire("p2", "scanner");
        small.acquire("p3", "scanner");
        small.acquire("p1", "scanner");
        small.acquire("p4", "scanner");

        assertThat(small.trackedKeys()).isEqualTo(3);
        assertThat(small.acquire("p2", "scanner"))
                .as("p2 was dropped, so it starts again")
                .isEmpty();
    }

    @Test
    void theWaitIsAtLeastOneSecond() {
        assertThat(PersonRouteRateLimiter.retryAfter(clock.instant().minusSeconds(60), clock.instant()))
                .isEqualTo(Duration.ofSeconds(1));
    }
}
