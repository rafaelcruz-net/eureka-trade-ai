package com.memetrade.marketdata.infra.coingecko;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.memetrade.marketdata.api.SourceStatus.State;
import com.memetrade.marketdata.domain.InMemoryMarketStore;
import com.memetrade.marketdata.domain.MarketCollector;
import com.memetrade.marketdata.domain.UnitOfWork;
import com.memetrade.marketdata.infra.MarketCollectorJob;
import com.memetrade.marketdata.infra.MarketDataProperties;
import com.memetrade.marketdata.infra.MarketSourceRegistry;
import com.memetrade.marketdata.infra.SourceCaller;
import com.memetrade.testsupport.InMemoryLockProvider;
import com.memetrade.testsupport.MutableClock;
import com.memetrade.testsupport.NoWaitSleeper;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Step 12 (NFR5.1 to NFR5.5, observability-design.md): the metrics of the real source through the worker schedule
 * ({@link CoinGeckoCollectorJob}), including {@code marketdata_source_state{source,state}}. Labels never carry the key,
 * a mint or a URL.
 */
class CoinGeckoMetricsTest {

    @RegisterExtension
    static WireMockExtension wire = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static final List<String> MINTS =
            IntStream.rangeClosed(301, 303).mapToObj(CoinGeckoFixtures::mint).toList();

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-14T12:00:00Z"));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final InMemoryMarketStore store = new InMemoryMarketStore();
    private final List<Runnable> started = new ArrayList<>();
    private CoinGeckoCollectorJob job;

    @BeforeEach
    void setUp() {
        CoinGeckoHttp http = new CoinGeckoHttp(
                wire.baseUrl() + "/api/v3",
                "x-cg-pro-api-key",
                CoinGeckoFixtures.TEST_KEY,
                Duration.ofSeconds(2),
                Duration.ofSeconds(3));
        CallBudget budget =
                new CallBudget("coingecko", 250, 2, 450_000, new CallBudgetTest.MemoryUsage(), clock, meters);
        CoinGeckoAdapter adapter = new CoinGeckoAdapter(
                CoinGeckoProperties.defaults().withBaseUrl(wire.baseUrl() + "/api/v3"),
                http,
                budget,
                clock,
                new CoinGeckoMetrics(meters, "coingecko"));
        MarketDataProperties properties = new MarketDataProperties(
                List.of("coingecko"),
                Duration.ofSeconds(30),
                new BigDecimal("5.0000"),
                new MarketDataProperties.Simulated(42, 60));
        SourceCaller caller = new SourceCaller(
                Executors.newCachedThreadPool(),
                new NoWaitSleeper(),
                new Random(42),
                SourceCaller.TIMEOUT,
                Map.of("coingecko", SourceCaller.COINGECKO_POLICY),
                (s, n) -> {});
        MarketCollectorJob.Settings base = new MarketCollectorJob.Settings(
                List.of(),
                new MarketCollector(store, caller, UnitOfWork.DIRECT, clock),
                store,
                new MarketSourceRegistry(List.of(), store, properties),
                new DefaultLockingTaskExecutor(new InMemoryLockProvider()),
                started::add,
                event -> {},
                Duration.ofSeconds(30),
                clock);
        job = new CoinGeckoCollectorJob(base, adapter, store, properties, meters);
        wire.stubFor(get(urlPathMatching(".*/tokens/.*/(info|pools)"))
                .willReturn(aResponse().withStatus(404)));
    }

    private double state(State state) {
        return meters.get("marketdata_source_state")
                .tag("source", "coingecko")
                .tag("state", state.name())
                .gauge()
                .value();
    }

    @Test
    void theSourceStateGaugeIsOneForTheCurrentStateOnly() {
        wire.stubFor(get(urlPathMatching(".*/(new|trending)_pools"))
                .willReturn(aResponse().withStatus(503)));

        assertThat(state(State.PENDING)).isEqualTo(1);
        assertThat(job.collectNow())
                .hasValueSatisfying(o -> assertThat(o.status()).isEqualTo(State.DEGRADED));
        assertThat(state(State.DEGRADED)).isEqualTo(1);
        clock.advance(Duration.ofSeconds(30));
        assertThat(job.collectNow())
                .hasValueSatisfying(o -> assertThat(o.status()).isEqualTo(State.FAILED));
        assertThat(state(State.FAILED)).isEqualTo(1);
        assertThat(state(State.OK) + state(State.DEGRADED) + state(State.PENDING))
                .isZero();

        job.dispatch();
        assertThat(started)
                .as("the dispatcher hands the due collection to the executor")
                .hasSize(1);
    }

    @Test
    void theRoundMetricsCountCallsValuesAndTimesWithoutKeyMintOrUrlInTheLabels() {
        wire.stubFor(get(urlPathEqualTo("/api/v3/onchain/networks/solana/new_pools"))
                .willReturn(okJson(CoinGeckoFixtures.pools(MINTS))));
        wire.stubFor(get(urlPathEqualTo("/api/v3/onchain/networks/solana/trending_pools"))
                .willReturn(okJson(CoinGeckoFixtures.pools(List.of()))));
        wire.stubFor(get(urlPathMatching(".*/token_price/.*")).willReturn(okJson(CoinGeckoFixtures.prices(MINTS))));

        assertThat(job.collectNow())
                .hasValueSatisfying(o -> assertThat(o.status()).isEqualTo(State.OK));

        assertThat(meters.get("marketdata_provider_calls_total")
                        .tag("kind", "discovery")
                        .tag("outcome", "ok")
                        .counter()
                        .count())
                .isEqualTo(2);
        assertThat(meters.get("marketdata_provider_calls_total")
                        .tag("kind", "detail")
                        .tag("outcome", "error")
                        .counter()
                        .count())
                .isPositive();
        assertThat(meters.get("marketdata_observations_total")
                        .tag("field", "PRICE")
                        .counter()
                        .count())
                .isEqualTo(3);
        assertThat(meters.get("marketdata_round_seconds")
                        .tag("kind", "price")
                        .timer()
                        .count())
                .isEqualTo(1);
        assertThat(meters.get("marketdata_active_tokens").gauge().value()).isEqualTo(3);
        assertThat(meters.get("marketdata_provider_budget_ratio").gauge().value())
                .as("5 calls of 450 000, rounded to 4 decimals")
                .isZero();
        assertThat(meters.get("marketdata_provider_conservative_mode").gauge().value())
                .isZero();
        for (Meter meter : meters.getMeters()) {
            for (Tag tag : meter.getId().getTags()) {
                assertThat(tag.getValue())
                        .as(meter.getId().getName() + " " + tag.getKey())
                        .doesNotContain(CoinGeckoFixtures.TEST_KEY)
                        .doesNotContain("http")
                        .isNotIn(MINTS);
            }
        }
    }

    @Test
    void theUsageAnswerIsReadAsAWholeNumberOrIgnored() throws Exception {
        ObjectMapper json = new ObjectMapper();

        assertThat(CoinGeckoUsageJob.monthlyCalls(json.readTree("{\"current_total_monthly_calls\": 1234}")))
                .hasValue(1234);
        assertThat(CoinGeckoUsageJob.monthlyCalls(json.readTree("{\"current_total_monthly_calls\": \"77\"}")))
                .hasValue(77);
        assertThat(CoinGeckoUsageJob.monthlyCalls(json.readTree("{\"current_total_monthly_calls\": -1}")))
                .isEmpty();
        assertThat(CoinGeckoUsageJob.monthlyCalls(json.readTree("{\"plan\": \"x\"}")))
                .isEmpty();
        assertThat(CoinGeckoUsageJob.monthlyCalls(null)).isEmpty();
    }
}
