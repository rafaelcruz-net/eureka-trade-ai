package com.memetrade.botwallet.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.botwallet.web.BotWalletRateLimits.Route;
import com.memetrade.testsupport.MutableClock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Step 28 (loop-back 1 of Build and Test), written BEFORE the limiter (team.md Testing Posture, NFR1.9): per person, at
 * most 10 consent challenges per minute, 3 activations per day and 10 partial withdrawals per hour; above, the wait
 * until the oldest request leaves the window. The routes and the persons never share a counter.
 */
class BotWalletRateLimitsTest {

    private final MutableClock clock = new MutableClock();
    private final BotWalletRateLimits limits = new BotWalletRateLimits(clock);
    private final UUID person = UUID.randomUUID();

    private void acceptTimes(Route route, UUID who, int times) {
        for (int i = 0; i < times; i++) {
            assertThat(limits.acquire(route, who))
                    .as(route + " request " + (i + 1))
                    .isEmpty();
        }
    }

    @Test
    void theEleventhChallengeInAMinuteWaitsAndTheWindowFreesItAfterTheMinute() {
        acceptTimes(Route.CHALLENGE, person, 10);

        Optional<Duration> eleventh = limits.acquire(Route.CHALLENGE, person);
        clock.advance(Duration.ofSeconds(60));

        assertThat(eleventh).contains(Duration.ofSeconds(60));
        assertThat(limits.acquire(Route.CHALLENGE, person)).isEmpty();
    }

    @Test
    void theFourthActivationInADayWaitsUntilTheFirstLeavesTheDay() {
        acceptTimes(Route.ACTIVATION, person, 1);
        clock.advance(Duration.ofHours(2));
        acceptTimes(Route.ACTIVATION, person, 2);

        Optional<Duration> fourth = limits.acquire(Route.ACTIVATION, person);
        clock.advance(Duration.ofHours(22));

        assertThat(fourth).contains(Duration.ofHours(22));
        assertThat(limits.acquire(Route.ACTIVATION, person)).isEmpty();
        assertThat(limits.acquire(Route.ACTIVATION, person)).isPresent();
    }

    @Test
    void theEleventhPartialWithdrawalInAnHourWaitsAndTheHourFreesIt() {
        acceptTimes(Route.PARTIAL_WITHDRAWAL, person, 10);

        Optional<Duration> eleventh = limits.acquire(Route.PARTIAL_WITHDRAWAL, person);
        clock.advance(Duration.ofMinutes(59));
        Optional<Duration> stillInTheHour = limits.acquire(Route.PARTIAL_WITHDRAWAL, person);
        clock.advance(Duration.ofMinutes(1));

        assertThat(eleventh).contains(Duration.ofHours(1));
        assertThat(stillInTheHour).contains(Duration.ofMinutes(1));
        assertThat(limits.acquire(Route.PARTIAL_WITHDRAWAL, person)).isEmpty();
    }

    @Test
    void routesAndPersonsHaveTheirOwnCounters() {
        UUID other = UUID.randomUUID();
        acceptTimes(Route.ACTIVATION, person, 3);

        assertThat(limits.acquire(Route.ACTIVATION, person)).isPresent();
        acceptTimes(Route.ACTIVATION, other, 3);
        acceptTimes(Route.CHALLENGE, person, 10);
        acceptTimes(Route.PARTIAL_WITHDRAWAL, person, 10);
    }

    @Test
    void theLimitsAreTheOnesOfTheRequirement() {
        assertThat(Route.CHALLENGE.limit()).isEqualTo(10);
        assertThat(Route.CHALLENGE.window()).isEqualTo(Duration.ofMinutes(1));
        assertThat(Route.ACTIVATION.limit()).isEqualTo(3);
        assertThat(Route.ACTIVATION.window()).isEqualTo(Duration.ofDays(1));
        assertThat(Route.PARTIAL_WITHDRAWAL.limit()).isEqualTo(10);
        assertThat(Route.PARTIAL_WITHDRAWAL.window()).isEqualTo(Duration.ofHours(1));
    }
}
