package com.memetrade.marketdata.infra.coingecko;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.marketdata.api.FailureCategory;
import com.memetrade.marketdata.api.MarketSourceAdapter.FetchRequest;
import com.memetrade.marketdata.domain.SourceFetcher.Failed;
import com.memetrade.marketdata.domain.SourceFetcher.FetchOutcome;
import com.memetrade.marketdata.infra.SourceCaller;
import com.memetrade.marketdata.infra.SourceCaller.CallPolicy;
import com.memetrade.testsupport.NoWaitSleeper;
import com.memetrade.testsupport.ScriptedMarketSource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * Step 7 (reliability-design.md "Dois níveis de proteção"): the {@link SourceCaller} accepts a policy per source. The
 * real source gets 25 s and ONE attempt (each call retries inside the adapter, so retries never pile up); sim-a and
 * sim-b keep 5 s and 3 attempts.
 */
class CoinGeckoSourceCallerTest {

    private final NoWaitSleeper sleeper = new NoWaitSleeper();
    private final List<Integer> retries = new ArrayList<>();
    private final SourceCaller caller = new SourceCaller(
            Executors.newCachedThreadPool(),
            sleeper,
            new Random(7),
            SourceCaller.TIMEOUT,
            Map.of("coingecko", SourceCaller.COINGECKO_POLICY),
            (source, attempt) -> retries.add(attempt));
    private final FetchRequest request = new FetchRequest(Instant.parse("2026-11-14T12:00:00Z"));

    @Test
    void theRealSourceHasOneAttemptOfTwentyFiveSeconds() {
        assertThat(caller.policy("coingecko")).isEqualTo(new CallPolicy(Duration.ofSeconds(25), 1));
        assertThat(caller.policy("sim-a")).isEqualTo(new CallPolicy(Duration.ofSeconds(5), 3));
    }

    @Test
    void aRetryableFailureOfTheRealSourceIsNotRetriedByTheCaller() {
        ScriptedMarketSource source = new ScriptedMarketSource(
                        com.memetrade.testsupport.FixtureMarketSource.load("sim-a")
                                .as("coingecko"))
                .thenFail(FailureCategory.ERROR_RESPONSE)
                .thenFail(FailureCategory.ERROR_RESPONSE);

        FetchOutcome outcome = caller.fetch(source, request);

        assertThat(outcome).isInstanceOf(Failed.class);
        assertThat(source.calls()).isEqualTo(1);
        assertThat(retries).isEmpty();
        assertThat(sleeper.waits()).isEmpty();
    }

    @Test
    void theSimulatedSourcesKeepTheirThreeAttempts() {
        ScriptedMarketSource source = ScriptedMarketSource.of("sim-b")
                .thenFail(FailureCategory.TIMEOUT)
                .thenFail(FailureCategory.TIMEOUT)
                .thenFail(FailureCategory.TIMEOUT);

        FetchOutcome outcome = caller.fetch(source, request);

        assertThat(outcome).isInstanceOf(Failed.class);
        assertThat(source.calls()).isEqualTo(3);
        assertThat(retries).containsExactly(1, 2);
    }

    @Test
    void aPolicyNeedsAPositiveLimitAndAtLeastOneAttempt() {
        assertThatThrownBy(() -> new CallPolicy(Duration.ZERO, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CallPolicy(Duration.ofSeconds(1), 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
