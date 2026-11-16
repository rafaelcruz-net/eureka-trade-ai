package com.memetrade.marketdata.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketSourceAdapter;
import com.memetrade.marketdata.api.MarketSourceAdapter.FetchRequest;
import com.memetrade.marketdata.api.MarketSourceAdapter.FetchResult;
import com.memetrade.marketdata.api.MarketSourceAdapter.Observation;
import com.memetrade.marketdata.api.MarketSourceAdapter.TokenInfo;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.domain.ObservationValidator;
import com.memetrade.marketdata.infra.simulated.SimulatedMarketSource;
import com.memetrade.shared.config.RecordedSwapFixture;
import com.memetrade.testsupport.MutableClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 261113-trading-real-bot U4 (infrastructure-specification.md "Decisão do bot", test-after: adapter and configuration):
 * in the test profile with the recorded swap, sim-a also publishes the recorded mint and the wrapped SOL with fresh,
 * fixed values after its generated tokens; sim-b and every other profile stay exactly as before.
 */
class RecordedSwapTokensTest {

    private static final String MINT = "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263";
    private static final RecordedSwapFixture FIXTURE = new RecordedSwapFixture(MINT, 5, 50_000_000L, 154_230_000_000L);
    private static final TokenId RECORDED = new TokenId("solana", MINT);
    private static final TokenId WSOL = new TokenId("solana", RecordedSwapFixture.WRAPPED_SOL);

    private final MutableClock clock = new MutableClock();
    private final MarketDataConfig config = new MarketDataConfig();
    private final MarketDataProperties properties = new MarketDataProperties(
            List.of("sim-a", "sim-b"),
            Duration.ofSeconds(30),
            new BigDecimal("5.0000"),
            new MarketDataProperties.Simulated(42, 200));

    private static MockEnvironment profile(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }

    private List<MarketSourceAdapter> sources(Optional<RecordedSwapFixture> fixture) {
        return config.configuredSources(properties, clock, profile("test"), fixture)
                .adapters();
    }

    private static Map<MarketField, MarketValue> values(FetchResult result, TokenId token) {
        return result.observations().stream()
                .filter(o -> o.token().equals(token))
                .collect(Collectors.toMap(Observation::field, Observation::value));
    }

    @Test
    void simAPublishesTheRecordedMintAndTheWrappedSolAfterItsGeneratedTokens() {
        FetchResult result = sources(Optional.of(FIXTURE)).get(0).fetch(new FetchRequest(clock.instant()));

        assertThat(result.tokens()).hasSize(202);
        assertThat(result.tokens().subList(200, 202))
                .extracting(TokenInfo::token, TokenInfo::decimals)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(WSOL, 9), org.assertj.core.groups.Tuple.tuple(RECORDED, 5));
        Map<MarketField, MarketValue> recorded = values(result, RECORDED);
        assertThat(recorded.keySet())
                .containsExactlyInAnyOrder(
                        MarketField.PRICE,
                        MarketField.LIQUIDITY,
                        MarketField.VOLUME_24H,
                        MarketField.HOLDERS,
                        MarketField.CREATED_AT);
        assertThat(((MarketValue.Known) recorded.get(MarketField.PRICE)).value())
                .isEqualByComparingTo(FIXTURE.tokenUsd());
        assertThat(((MarketValue.Known) values(result, WSOL).get(MarketField.PRICE)).value())
                .isEqualByComparingTo(RecordedSwapFixture.SOL_USD);
        assertThat(ObservationValidator.validate(
                                result, sources(Optional.of(FIXTURE)).get(0).source())
                        .discardedTotal())
                .isZero();
    }

    @Test
    void thePinnedValuesAreFreshOnEveryFetch() {
        MarketSourceAdapter simA = sources(Optional.of(FIXTURE)).get(0);
        clock.advance(Duration.ofMinutes(10));

        MarketValue price =
                values(simA.fetch(new FetchRequest(clock.instant())), RECORDED).get(MarketField.PRICE);

        assertThat(((MarketValue.Known) price).observedAt()).isEqualTo(clock.instant());
        assertThat(((MarketValue.Known) price).value()).isEqualByComparingTo(FIXTURE.tokenUsd());
    }

    @Test
    void simBAndTheGeneratedTokensAreUnchanged() {
        List<MarketSourceAdapter> pinned = sources(Optional.of(FIXTURE));
        List<MarketSourceAdapter> plain = sources(Optional.empty());
        FetchRequest request = new FetchRequest(clock.instant());

        FetchResult pinnedA = pinned.get(0).fetch(request);
        FetchResult plainA = plain.get(0).fetch(request);
        assertThat(pinnedA.tokens().subList(0, 200)).isEqualTo(plainA.tokens());
        assertThat(pinnedA.observations().subList(0, plainA.observations().size()))
                .isEqualTo(plainA.observations());
        assertThat(pinned.get(1).fetch(request).tokens()).hasSize(200).doesNotContainNull();
        assertThat(pinned.get(1).fetch(request).tokens())
                .extracting(TokenInfo::token)
                .doesNotContain(RECORDED, WSOL);
    }

    @Test
    void outsideTheTestProfileARecordingStopsTheStart() {
        assertThatThrownBy(() -> config.configuredSources(properties, clock, profile("local"), Optional.of(FIXTURE)))
                .hasMessageContaining("RECORDED_MINT");
    }

    @Test
    void aPinnedTokenCannotRepeatAGeneratedOne() {
        TokenId generated = SimulatedMarketSource.forSource("sim-a", 42, 50, clock)
                .fetch(new FetchRequest(clock.instant()))
                .tokens()
                .get(0)
                .token();
        SimulatedMarketSource.Pinned repeated = new SimulatedMarketSource.Pinned(
                new TokenInfo(generated, "DUP", "Duplicado", 6),
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                Duration.ofDays(1));

        assertThatThrownBy(() -> SimulatedMarketSource.forSource(
                        "sim-a", 42, 50, clock, SimulatedMarketSource.Scenario.STANDARD, List.of(repeated)))
                .hasMessageContaining("repeats");
        assertThatThrownBy(() -> new SimulatedMarketSource.Pinned(
                        new TokenInfo(RECORDED, "X", "X", 5),
                        BigDecimal.ZERO,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        Duration.ZERO))
                .hasMessageContaining("price");
    }
}
