package com.memetrade.marketdata.infra.coingecko;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.memetrade.marketdata.api.FailureCategory;
import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketSourceAdapter.FetchRequest;
import com.memetrade.marketdata.api.MarketSourceAdapter.FetchResult;
import com.memetrade.marketdata.api.MarketSourceAdapter.Observation;
import com.memetrade.marketdata.api.MarketSourceAdapter.RoundSummary;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.api.UnknownReason;
import com.memetrade.testsupport.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Step 6 (BR1.1, BR1.3, BR1.9, NFR4.2, NFR4.6): the rounds of the CoinGecko adapter against WireMock with synthetic
 * answers. The latency is FIXED and deterministic: each call moves the injectable clock by {@code step} (the WireMock
 * delay itself is zero), so the deadlines are tested without waiting.
 */
class CoinGeckoAdapterTest {

    @RegisterExtension
    static WireMockExtension wire = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static final Instant START = Instant.parse("2026-11-14T12:00:00Z");
    private static final List<String> MINTS =
            IntStream.rangeClosed(1, 120).mapToObj(CoinGeckoFixtures::mint).toList();

    private final MutableClock clock = new MutableClock(START);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final CallBudgetTest.MemoryUsage usage = new CallBudgetTest.MemoryUsage();
    private SteppingHttp http;
    private CallBudget budget;
    private CoinGeckoAdapter adapter;

    @BeforeEach
    void setUp() {
        CoinGeckoProperties properties = CoinGeckoProperties.defaults().withBaseUrl(wire.baseUrl() + "/api/v3");
        http = new SteppingHttp(wire.baseUrl() + "/api/v3", clock);
        budget = new CallBudget("coingecko", 250, 2, 450_000, usage, clock, meters);
        adapter = new CoinGeckoAdapter(properties, http, budget, clock, new CoinGeckoMetrics(meters, "coingecko"));
        wire.stubFor(get(urlPathMatching("/api/v3/onchain/simple/networks/solana/token_price/.*"))
                .willReturn(okJson(CoinGeckoFixtures.prices(MINTS))));
        wire.stubFor(get(urlPathMatching("/api/v3/onchain/networks/solana/tokens/.*/info"))
                .willReturn(okJson(CoinGeckoFixtures.info(MINTS.get(0), "1500"))));
        wire.stubFor(get(urlPathMatching("/api/v3/onchain/networks/solana/tokens/.*/pools"))
                .willReturn(okJson(CoinGeckoFixtures.tokenPools("2026-11-10T08:00:00Z"))));
    }

    private void discovery(List<String> fresh, List<String> trending) {
        wire.stubFor(get(urlPathEqualTo("/api/v3/onchain/networks/solana/new_pools"))
                .willReturn(okJson(CoinGeckoFixtures.pools(fresh))));
        wire.stubFor(get(urlPathEqualTo("/api/v3/onchain/networks/solana/trending_pools"))
                .willReturn(okJson(CoinGeckoFixtures.pools(trending))));
    }

    private FetchResult round() {
        return adapter.fetch(new FetchRequest(clock.instant()));
    }

    private static RoundSummary summary(FetchResult result) {
        return result.round().orElseThrow();
    }

    private int priceCalls() {
        return wire.findAll(getRequestedFor(urlPathMatching(".*/token_price/.*")))
                .size();
    }

    private int discoveryCalls() {
        return wire.findAll(getRequestedFor(urlPathMatching(".*/(new|trending)_pools")))
                .size();
    }

    @Test
    void theFirstRoundDiscoversWithoutRepeatedMintsAndPricesTheNewTokensAtOnce() {
        discovery(MINTS.subList(0, 3), MINTS.subList(2, 5));

        FetchResult result = round();

        assertThat(result.tokens())
                .extracting(t -> t.token().mint())
                .containsExactlyInAnyOrderElementsOf(MINTS.subList(0, 5));
        assertThat(summary(result)).isEqualTo(new RoundSummary(5, 5, Optional.empty()));
        assertThat(summary(result).complete()).isTrue();
        assertThat(adapter.tolerantFailure()).isTrue();
        assertThat(value(result, MINTS.get(0), MarketField.PRICE))
                .isEqualTo(new MarketValue.Known(new java.math.BigDecimal("1.5"), CoinGeckoSource.REF, START, false));
    }

    @Test
    void pricesGoInBatchesOfThirtyAndTheDiscoveryKeepsAtMostFiftyTokens() {
        discovery(MINTS.subList(0, 40), MINTS.subList(30, 70));

        FetchResult result = round();

        assertThat(summary(result).activeTokens()).isEqualTo(50);
        assertThat(priceCalls()).as("50 tokens are 2 price calls").isEqualTo(2);
        assertThat(result.observations().stream().filter(o -> o.field() == MarketField.PRICE))
                .hasSize(50);
    }

    @Test
    void theWatchedTokensJoinTheActiveSetAndStayWhenTheDiscoveryDropsThem() {
        discovery(MINTS.subList(0, 2), List.of());
        round();
        TokenId watched = new TokenId("solana", MINTS.get(1));

        clock.advance(Duration.ofMinutes(5));
        discovery(List.of(MINTS.get(0)), List.of());
        FetchResult result = adapter.fetch(new FetchRequest(clock.instant(), Set.of(watched)));

        assertThat(adapter.activeTokens()).contains(watched, new TokenId("solana", MINTS.get(0)));
        clock.advance(Duration.ofMinutes(5));
        round();
        assertThat(adapter.activeTokens())
                .as("not discovered and no longer watched")
                .doesNotContain(watched);
        assertThat(result.tokens()).isNotEmpty();
    }

    @Test
    void theDiscoveryRunsEveryFiveMinutesOnlyAndThePricesEveryRound() {
        discovery(MINTS.subList(0, 3), List.of());
        round();
        clock.advance(Duration.ofSeconds(30));
        round();
        assertThat(discoveryCalls()).isEqualTo(2);
        assertThat(priceCalls()).isEqualTo(2);

        clock.advance(Duration.ofMinutes(5));
        round();
        assertThat(discoveryCalls()).isEqualTo(4);
    }

    @Test
    void theDetailsBringHoldersAndCreationWithinTwoCallsPerMinute() {
        discovery(MINTS.subList(0, 3), List.of());

        FetchResult first = round();

        assertThat(wire.findAll(getRequestedFor(urlPathMatching(".*/tokens/.*/(info|pools)"))))
                .as("2 detail calls per minute")
                .hasSize(2);
        assertThat(first.observations())
                .filteredOn(o -> o.field() == MarketField.HOLDERS)
                .extracting(Observation::value)
                .allSatisfy(v -> assertThat(((MarketValue.Known) v).value()).isEqualByComparingTo("1500"));
        clock.advance(Duration.ofSeconds(61));
        FetchResult second = round();
        assertThat(second.observations()).anySatisfy(o -> assertThat(o.field()).isEqualTo(MarketField.CREATED_AT));
    }

    @Test
    void onlyTheRenewedFieldsAreReturnedSoTheOthersKeepTheirObservation() {
        discovery(MINTS.subList(0, 3), List.of());
        round();
        clock.advance(Duration.ofSeconds(30));

        FetchResult second = round();

        assertThat(second.observations())
                .extracting(Observation::field)
                .containsOnly(MarketField.PRICE, MarketField.LIQUIDITY, MarketField.VOLUME_24H);
    }

    @Test
    void noCallOpensAfterTwelveSecondsAndTheCutRoundIsATimeout() {
        CoinGeckoProperties tenPerBatch = new CoinGeckoProperties(
                wire.baseUrl() + "/api/v3",
                null,
                null,
                10,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        adapter = new CoinGeckoAdapter(tenPerBatch, http, budget, clock, new CoinGeckoMetrics(meters, "coingecko"));
        discovery(MINTS.subList(0, 100), List.of());
        round();
        clock.advance(Duration.ofSeconds(30));
        http.step = Duration.ofSeconds(5);
        wire.resetRequests();

        FetchResult cut = round();

        assertThat(priceCalls())
                .as("batches at 0 s, 5 s and 10 s; none at 15 s")
                .isEqualTo(3);
        assertThat(summary(cut).cut()).contains(FailureCategory.TIMEOUT);
        assertThat(summary(cut).pricedTokens()).isEqualTo(30);
        assertThat(summary(cut).activeTokens()).isEqualTo(50);
        assertThat(cut.observations()).noneMatch(o -> o.field() == MarketField.HOLDERS);
    }

    @Test
    void theDiscoveryNeedsEightSecondsLeftAndTheDetailsFour() {
        discovery(MINTS.subList(0, 30), List.of());
        round();
        wire.resetRequests();

        clock.advance(Duration.ofMinutes(5));
        http.step = Duration.ofSeconds(11);
        round();
        assertThat(discoveryCalls())
                .as("7 s left after the prices: the due discovery waits")
                .isZero();
        assertThat(wire.findAll(getRequestedFor(urlPathMatching(".*/tokens/.*/(info|pools)"))))
                .as("7 s left: one detail call still fits")
                .hasSize(1);

        clock.advance(Duration.ofMinutes(5));
        http.step = Duration.ofSeconds(15);
        round();
        assertThat(discoveryCalls()).isZero();
        assertThat(wire.findAll(getRequestedFor(urlPathMatching(".*/tokens/.*/(info|pools)"))))
                .as("3 s left: no detail")
                .hasSize(1);
    }

    @Test
    void a429IsNotRetriedCutsTheRoundAndItsRetryAfterHoldsTheNextRounds() {
        discovery(MINTS.subList(0, 3), List.of());
        round();
        wire.stubFor(get(urlPathMatching(".*/token_price/.*"))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "60")));
        wire.resetRequests();
        clock.advance(Duration.ofSeconds(30));

        FetchResult limited = round();
        assertThat(priceCalls()).isEqualTo(1);
        assertThat(summary(limited).cut()).contains(FailureCategory.ERROR_RESPONSE);
        assertThat(summary(limited).noPrice()).isTrue();

        clock.advance(Duration.ofSeconds(30));
        round();
        assertThat(priceCalls()).as("still inside Retry-After: no call").isEqualTo(1);
        clock.advance(Duration.ofSeconds(31));
        round();
        assertThat(priceCalls()).isEqualTo(2);
    }

    @Test
    void aTimeoutOrA5xxIsRetriedOnceAndThenSucceeds() {
        discovery(MINTS.subList(0, 3), List.of());
        round();
        wire.stubFor(get(urlPathMatching(".*/token_price/.*"))
                .inScenario("flaky")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovered"));
        wire.stubFor(get(urlPathMatching(".*/token_price/.*"))
                .inScenario("flaky")
                .whenScenarioStateIs("recovered")
                .willReturn(okJson(CoinGeckoFixtures.prices(MINTS))));
        clock.advance(Duration.ofSeconds(30));
        wire.resetRequests();

        FetchResult result = round();

        assertThat(priceCalls()).isEqualTo(2);
        assertThat(summary(result).complete()).isTrue();
        assertThat(meters.get("marketdata_provider_calls_total")
                        .tag("kind", "price")
                        .tag("outcome", "retry")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void anotherClientErrorIsNotRetriedAndLeavesTheRoundWithoutPrice() {
        discovery(MINTS.subList(0, 3), List.of());
        round();
        wire.stubFor(
                get(urlPathMatching(".*/token_price/.*")).willReturn(aResponse().withStatus(400)));
        clock.advance(Duration.ofSeconds(30));
        wire.resetRequests();

        FetchResult result = round();

        assertThat(priceCalls()).isEqualTo(1);
        assertThat(summary(result).noPrice()).isTrue();
        assertThat(summary(result).cut()).contains(FailureCategory.ERROR_RESPONSE);
    }

    @Test
    void theCircuitOpensAfterFiveTechnicalFailuresAndStopsCallingTheProvider() {
        discovery(MINTS.subList(0, 3), List.of());
        round();
        wire.stubFor(
                get(urlPathMatching(".*/token_price/.*")).willReturn(aResponse().withStatus(500)));
        wire.resetRequests();
        for (int i = 0; i < 4; i++) {
            clock.advance(Duration.ofSeconds(5));
            round();
        }
        int beforeOpen = priceCalls();

        clock.advance(Duration.ofSeconds(5));
        FetchResult open = round();

        assertThat(beforeOpen).isGreaterThanOrEqualTo(5);
        assertThat(priceCalls()).as("the open circuit makes no call").isEqualTo(beforeOpen);
        assertThat(summary(open).cut()).contains(FailureCategory.ERROR_RESPONSE);
    }

    @Test
    void aTokenWithoutPriceIsUnknownNotProvidedAndTheRoundIsNotComplete() {
        discovery(MINTS.subList(0, 2), List.of());
        wire.stubFor(get(urlPathMatching(".*/token_price/.*"))
                .willReturn(okJson(
                        CoinGeckoFixtures.prices(Map.of(MINTS.get(0), "2"), Map.of(MINTS.get(0), "-5"), Map.of()))));

        FetchResult result = round();

        assertThat(value(result, MINTS.get(1), MarketField.PRICE))
                .isEqualTo(new MarketValue.Unknown(UnknownReason.NOT_PROVIDED, Optional.of(CoinGeckoSource.REF)));
        assertThat(value(result, MINTS.get(0), MarketField.VOLUME_24H)).isInstanceOf(MarketValue.Unknown.class);
        assertThat(summary(result)).isEqualTo(new RoundSummary(2, 1, Optional.empty()));
        assertThat(summary(result).complete()).isFalse();
        assertThat(meters.get("marketdata_invalid_values_total")
                        .tag("field", "VOLUME_24H")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void anExhaustedBudgetStopsThePricesWithoutCallingAndTheGaugesShowTheActiveSet() {
        discovery(MINTS.subList(0, 3), List.of());
        round();
        usage.set("coingecko", "2026-11", 450_000);
        wire.resetRequests();
        clock.advance(Duration.ofSeconds(40));

        FetchResult result = round();

        assertThat(priceCalls()).isZero();
        assertThat(summary(result).cut()).contains(FailureCategory.ERROR_RESPONSE);
        assertThat(meters.get("marketdata_active_tokens").gauge().value()).isEqualTo(3);
        assertThat(meters.get("marketdata_oldest_price_age_seconds").gauge().value())
                .isEqualTo(40);
        assertThat(meters.get("marketdata_observations_total")
                        .tag("field", "PRICE")
                        .counter()
                        .count())
                .isEqualTo(3);
    }

    @Test
    void anOpenCircuitMakesNoCallAndCountsNothingInTheBudget() {
        io.github.resilience4j.circuitbreaker.CircuitBreaker circuit = CoinGeckoAdapter.defaultCircuit();
        adapter = new CoinGeckoAdapter(
                CoinGeckoProperties.defaults().withBaseUrl(wire.baseUrl() + "/api/v3"),
                http,
                budget,
                clock,
                new CoinGeckoMetrics(meters, "coingecko"),
                circuit);
        discovery(MINTS.subList(0, 3), List.of());
        round();
        long counted = usage.calls("coingecko", "2026-11");
        circuit.transitionToForcedOpenState();
        wire.resetRequests();
        clock.advance(Duration.ofMinutes(5));

        FetchResult open = round();

        assertThat(wire.getAllServeEvents()).isEmpty();
        assertThat(usage.calls("coingecko", "2026-11"))
                .as("nothing counted while open")
                .isEqualTo(counted);
        assertThat(summary(open).cut()).contains(FailureCategory.ERROR_RESPONSE);
        assertThat(summary(open).noPrice()).isTrue();
    }

    @Test
    void aDetailThatFailedIsNotRenewedAndIsTriedAgainInTheNextRound() {
        discovery(MINTS.subList(0, 1), List.of());
        wire.stubFor(get(urlPathMatching(".*/tokens/.*/info"))
                .inScenario("info")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500)));

        FetchResult first = round();
        assertThat(first.observations()).noneMatch(o -> o.field() == MarketField.HOLDERS);

        wire.stubFor(get(urlPathMatching(".*/tokens/.*/info"))
                .inScenario("info")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(CoinGeckoFixtures.info(MINTS.get(0), "1500"))));
        clock.advance(Duration.ofSeconds(61));
        FetchResult second = round();

        assertThat(second.observations()).anySatisfy(o -> assertThat(o.field()).isEqualTo(MarketField.HOLDERS));
        assertThat(second.observations()).anySatisfy(o -> assertThat(o.field()).isEqualTo(MarketField.CREATED_AT));
    }

    private static MarketValue value(FetchResult result, String mint, MarketField field) {
        return result.observations().stream()
                .filter(o -> o.token().mint().equals(mint) && o.field() == field)
                .map(Observation::value)
                .findFirst()
                .orElseThrow();
    }

    /** The real client whose every call moves the injectable clock by a fixed latency. */
    static final class SteppingHttp extends CoinGeckoHttp {

        private final MutableClock clock;
        Duration step = Duration.ZERO;

        SteppingHttp(String baseUrl, MutableClock clock) {
            super(
                    baseUrl,
                    "x-cg-pro-api-key",
                    CoinGeckoFixtures.TEST_KEY,
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(3));
            this.clock = clock;
        }

        @Override
        JsonNode get(Route route, String pathAndQuery) {
            try {
                return super.get(route, pathAndQuery);
            } finally {
                clock.advance(step);
            }
        }
    }
}
