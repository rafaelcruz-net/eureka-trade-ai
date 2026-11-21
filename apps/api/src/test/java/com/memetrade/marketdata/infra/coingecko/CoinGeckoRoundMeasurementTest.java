package com.memetrade.marketdata.infra.coingecko;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.memetrade.marketdata.api.MarketSourceAdapter.FetchRequest;
import com.memetrade.marketdata.api.MarketSourceAdapter.FetchResult;
import com.memetrade.testsupport.MutableClock;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * 261113-trading-real-bot, Performance Validation: the synthetic measurement that U2:NFR3.1 to NFR3.3 ask for before
 * production ("com WireMock e latência simulada de 300 ms por chamada"). Every call to the CoinGecko double moves the
 * injectable clock by exactly 300 ms, so the durations the adapter records in {@code marketdata_round_seconds} are the
 * durations of the design, measured without waiting and without network (NFR8.2). The limits are those of the
 * requirements: the price part with 50 tokens up to 10 s, the discovery up to 5 s, the details up to 5 s, and the whole
 * round within the 20 s of NFR4.6. The real API is measured only after production (the CoinGecko has no devnet).
 */
@Tag("u2-real-market-data")
class CoinGeckoRoundMeasurementTest {

    @RegisterExtension
    static WireMockExtension wire = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static final Duration PER_CALL = Duration.ofMillis(300);
    private static final List<String> MINTS =
            IntStream.rangeClosed(1, 120).mapToObj(CoinGeckoFixtures::mint).toList();

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-20T12:00:00Z"));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private CoinGeckoAdapter adapter;

    @BeforeEach
    void setUp() {
        CoinGeckoProperties properties = CoinGeckoProperties.defaults().withBaseUrl(wire.baseUrl() + "/api/v3");
        CoinGeckoAdapterTest.SteppingHttp http =
                new CoinGeckoAdapterTest.SteppingHttp(wire.baseUrl() + "/api/v3", clock);
        http.step = PER_CALL;
        CallBudget budget =
                new CallBudget("coingecko", 250, 2, 450_000, new CallBudgetTest.MemoryUsage(), clock, meters);
        adapter = new CoinGeckoAdapter(properties, http, budget, clock, new CoinGeckoMetrics(meters, "coingecko"));
        wire.stubFor(get(urlPathMatching("/api/v3/onchain/simple/networks/solana/token_price/.*"))
                .willReturn(okJson(CoinGeckoFixtures.prices(MINTS))));
        wire.stubFor(get(urlPathMatching("/api/v3/onchain/networks/solana/tokens/.*/info"))
                .willReturn(okJson(CoinGeckoFixtures.info(MINTS.get(0), "1500"))));
        wire.stubFor(get(urlPathMatching("/api/v3/onchain/networks/solana/tokens/.*/pools"))
                .willReturn(okJson(CoinGeckoFixtures.tokenPools("2026-11-10T08:00:00Z"))));
        // 40 new and 40 trending pools: the discovery keeps 50 active tokens.
        wire.stubFor(get(urlPathEqualTo("/api/v3/onchain/networks/solana/new_pools"))
                .willReturn(okJson(CoinGeckoFixtures.pools(MINTS.subList(0, 40)))));
        wire.stubFor(get(urlPathEqualTo("/api/v3/onchain/networks/solana/trending_pools"))
                .willReturn(okJson(CoinGeckoFixtures.pools(MINTS.subList(30, 70)))));
    }

    private Duration timed(Runnable round) {
        Instant start = clock.instant();
        round.run();
        return Duration.between(start, clock.instant());
    }

    private FetchResult round() {
        return adapter.fetch(new FetchRequest(clock.instant()));
    }

    private Timer part(String kind) {
        Timer timer = meters.find("marketdata_round_seconds").tag("kind", kind).timer();
        assertThat(timer).as("the adapter records the %s part", kind).isNotNull();
        return timer;
    }

    private int calls(String pattern) {
        return wire.findAll(getRequestedFor(urlPathMatching(pattern))).size();
    }

    @Test
    void withThreeHundredMillisecondsPerCallEveryPartAndTheWholeRoundStayWithinTheirLimits() {
        Duration first = timed(this::round);
        assertThat(round().tokens())
                .as("the second round prices the 50 active tokens")
                .hasSize(50);
        clock.advance(Duration.ofSeconds(61));
        Duration third = timed(this::round);

        // NFR3.1: the price part with 50 tokens is two calls of 30 (U2 BR1.3), 600 ms here, against 10 s.
        Timer price = part("price");
        assertThat(price.count()).isEqualTo(3);
        assertThat(price.max(TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(10_000.0);
        // NFR3.2: the discovery is two calls (new and trending pools), 600 ms here, against 5 s.
        assertThat(calls(".*/(new|trending)_pools")).isEqualTo(2);
        assertThat(part("discovery").max(TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(5_000.0);
        // NFR3.3: the details are at most two calls per minute, against 5 s.
        assertThat(part("detail").max(TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(5_000.0);
        // NFR4.6: no round passes the total deadline of 20 s.
        assertThat(first).isLessThanOrEqualTo(Duration.ofSeconds(20));
        assertThat(third).isLessThanOrEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void theMeasurementIsTheSimulatedLatencyItselfSoASlowerDoubleWouldShowInTheTimers() {
        round();
        round();

        // The first round prices its new tokens inside the discovery, so its price part has no call; the price part of
        // the second round, with the 50 active tokens, is exactly its two calls times 300 ms. The timer measures the
        // latency of the calls and does not hide it: a double ten times slower would show 6 s here.
        Timer price = part("price");
        assertThat(price.count()).isEqualTo(2);
        assertThat(price.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(2 * PER_CALL.toMillis() * 1.0);
        assertThat(price.max(TimeUnit.MILLISECONDS)).isEqualTo(2 * PER_CALL.toMillis() * 1.0);
    }
}
