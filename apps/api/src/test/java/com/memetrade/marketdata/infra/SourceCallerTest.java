package com.memetrade.marketdata.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.marketdata.api.FailureCategory;
import com.memetrade.marketdata.api.MarketSourceAdapter.FetchRequest;
import com.memetrade.marketdata.api.MarketSourceException;
import com.memetrade.marketdata.domain.SourceFetcher.Failed;
import com.memetrade.marketdata.domain.SourceFetcher.FetchOutcome;
import com.memetrade.marketdata.domain.SourceFetcher.Fetched;
import com.memetrade.testsupport.NoWaitSleeper;
import com.memetrade.testsupport.ScriptedMarketSource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Time limit, new attempts and waits of a source call (NFR10.3), without waiting for real. */
@Tag("u4")
class SourceCallerTest {

    private final NoWaitSleeper sleeper = new NoWaitSleeper();
    private final List<Integer> retries = new ArrayList<>();
    private final SourceCaller caller = new SourceCaller(
            Executors.newCachedThreadPool(), sleeper, new Random(7), Duration.ofMillis(200), (s, n) -> retries.add(n));
    private final FetchRequest request = new FetchRequest(Instant.parse("2026-10-28T12:00:00Z"));

    @Test
    void aSuccessAtTheFirstCallHasNoRetryAndNoWait() {
        FetchOutcome outcome = caller.fetch(ScriptedMarketSource.of("sim-a"), request);

        assertThat(outcome).isInstanceOf(Fetched.class);
        assertThat(outcome.retries()).isZero();
        assertThat(sleeper.waits()).isEmpty();
    }

    @Test
    void threeFailuresGiveFailedAfterTwoNewAttemptsWithGrowingWaits() {
        ScriptedMarketSource source = ScriptedMarketSource.of("sim-a")
                .thenFail(FailureCategory.ERROR_RESPONSE)
                .thenFail(FailureCategory.ERROR_RESPONSE)
                .thenFail(FailureCategory.ERROR_RESPONSE);

        FetchOutcome outcome = caller.fetch(source, request);

        assertThat(outcome).isInstanceOf(Failed.class);
        assertThat(((Failed) outcome).category()).isEqualTo(FailureCategory.ERROR_RESPONSE);
        assertThat(source.calls()).isEqualTo(3);
        assertThat(retries).containsExactly(1, 2);
        assertThat(sleeper.waits().get(1)).isGreaterThan(sleeper.waits().get(0));
    }

    @Test
    void aFourHundredIsNotRetriedButATooManyRequestsIs() {
        ScriptedMarketSource refused =
                ScriptedMarketSource.of("sim-a").thenThrow(MarketSourceException.errorResponse(404));
        ScriptedMarketSource throttled =
                ScriptedMarketSource.of("sim-b").thenThrow(MarketSourceException.errorResponse(429));

        assertThat(caller.fetch(refused, request)).isInstanceOf(Failed.class);
        assertThat(refused.calls()).isEqualTo(1);
        FetchOutcome second = caller.fetch(throttled, request);
        assertThat(second).isInstanceOf(Fetched.class);
        assertThat(((Fetched) second).retryCause()).contains(FailureCategory.ERROR_RESPONSE);
    }

    @Test
    void aSlowCallIsATimeout() {
        ScriptedMarketSource slow = ScriptedMarketSource.of("sim-a").thenDelay(Duration.ofMillis(600));

        FetchOutcome outcome = caller.fetch(slow, request);

        assertThat(outcome).isInstanceOf(Fetched.class);
        assertThat(((Fetched) outcome).retryCause()).contains(FailureCategory.TIMEOUT);
    }

    @Test
    void theWaitsAreOneAndTwoSecondsWithinTwentyPercent() {
        for (int i = 0; i < 200; i++) {
            assertThat(caller.interval(1)).isBetween(800L, 1200L);
            assertThat(caller.interval(2)).isBetween(1600L, 2400L);
        }
    }

    @Test
    void theCategoriesFollowTheClosedList() {
        assertThat(SourceCaller.category(new TimeoutException())).isEqualTo(FailureCategory.TIMEOUT);
        assertThat(SourceCaller.category(new IllegalStateException())).isEqualTo(FailureCategory.ERROR_RESPONSE);
        assertThat(SourceCaller.category(MarketSourceException.invalidPayload("x")))
                .isEqualTo(FailureCategory.INVALID_PAYLOAD);
        assertThat(SourceCaller.retryable(MarketSourceException.invalidPayload("x")))
                .isFalse();
        assertThat(SourceCaller.retryable(new RuntimeException())).isTrue();
    }
}
