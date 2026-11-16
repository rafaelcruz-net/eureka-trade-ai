package com.memetrade.marketdata.infra.simulated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.marketdata.api.MarketSourceAdapter.FetchRequest;
import com.memetrade.marketdata.api.MarketSourceAdapter.Observation;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.infra.ConfiguredSources;
import com.memetrade.marketdata.infra.MarketDataConfig;
import com.memetrade.marketdata.infra.MarketDataProperties;
import com.memetrade.marketdata.infra.simulated.SimulatedMarketSource.Scenario;
import com.memetrade.testsupport.MutableClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * U5 Passo 20 (cicd-pipeline.md): the frequent-signal scenario of the simulated source, for the load scenario u5 only.
 * STANDARD stays exactly as in U1 and U4; FREQUENT_SIGNALS moves only the signal tokens along a 30-minute saw, in
 * exact decimals, and the configuration refuses it outside the test profile.
 */
@Tag("u5")
class FrequentSignalsScenarioTest {

    /** The start of a phase of the saw (a multiple of 30 minutes since the epoch). */
    private static final Instant PHASE_START = Instant.parse("2026-10-28T12:00:00Z");

    private final MutableClock clock = new MutableClock();

    private static Map<String, BigDecimal> values(List<Observation> observations, TokenId token) {
        return observations.stream()
                .filter(o -> o.token().equals(token) && o.value() instanceof MarketValue.Known)
                .collect(Collectors.toMap(o -> o.field().name(), o -> ((MarketValue.Known) o.value()).value()));
    }

    @Test
    void standardIsTheBehaviourOfU1AndU4() {
        SimulatedMarketSource before = new SimulatedMarketSource(SimulatedMarketSource.SOURCE_B, 42, 60, clock);
        SimulatedMarketSource standard =
                new SimulatedMarketSource(SimulatedMarketSource.SOURCE_B, 42, 60, clock, Scenario.STANDARD);

        assertThat(standard.fetch(new FetchRequest(PHASE_START)))
                .isEqualTo(before.fetch(new FetchRequest(PHASE_START)));
    }

    @Test
    void aSignalTokenRisesFifteenPercentInPriceAndSeventyFiveInVolumeOverFifteenMinutes() {
        SimulatedMarketSource source =
                SimulatedMarketSource.forSource("sim-a", 42, 60, clock, Scenario.FREQUENT_SIGNALS);
        TokenId signal =
                source.fetch(new FetchRequest(PHASE_START)).tokens().get(5).token();

        Map<String, BigDecimal> start =
                values(source.fetch(new FetchRequest(PHASE_START)).observations(), signal);
        Map<String, BigDecimal> later = values(
                source.fetch(new FetchRequest(PHASE_START.plus(Duration.ofMinutes(15))))
                        .observations(),
                signal);
        Map<String, BigDecimal> nextPhase = values(
                source.fetch(new FetchRequest(PHASE_START.plus(Duration.ofMinutes(30))))
                        .observations(),
                signal);

        assertThat(later.get("PRICE").divide(start.get("PRICE"), 2, java.math.RoundingMode.HALF_EVEN))
                .isEqualByComparingTo("1.15");
        assertThat(later.get("VOLUME_24H").divide(start.get("VOLUME_24H"), 2, java.math.RoundingMode.HALF_EVEN))
                .isEqualByComparingTo("1.75");
        assertThat(start.get("LIQUIDITY")).isGreaterThanOrEqualTo(new BigDecimal("1000000"));
        assertThat(nextPhase.get("PRICE")).isEqualByComparingTo(start.get("PRICE"));
        assertThat(start.get("PRICE").scale()).isEqualTo(9);
    }

    @Test
    void onlyTheSignalTokensMoveAndCommonTokensStayEqualInBothSources() {
        SimulatedMarketSource standard = SimulatedMarketSource.forSource("sim-a", 42, 60, clock);
        SimulatedMarketSource frequentA =
                SimulatedMarketSource.forSource("sim-a", 42, 60, clock, Scenario.FREQUENT_SIGNALS);
        SimulatedMarketSource frequentB =
                SimulatedMarketSource.forSource("sim-b", 42, 60, clock, Scenario.FREQUENT_SIGNALS);
        Instant at = PHASE_START.plus(Duration.ofMinutes(7));
        var plain = standard.fetch(new FetchRequest(at));
        var a = frequentA.fetch(new FetchRequest(at));
        var b = frequentB.fetch(new FetchRequest(at));

        for (int i = 0; i < 50; i++) {
            TokenId token = plain.tokens().get(i).token();
            if (SimulatedMarketSource.isSignalToken(i)) {
                assertThat(values(a.observations(), token).get("PRICE"))
                        .isEqualByComparingTo(values(b.observations(), token).get("PRICE"));
            } else {
                assertThat(values(a.observations(), token)).isEqualTo(values(plain.observations(), token));
            }
        }
        assertThat(SimulatedMarketSource.isSignalToken(1)).isFalse();
        assertThat(SimulatedMarketSource.isSignalToken(13)).isFalse();
        assertThat(SimulatedMarketSource.isSignalToken(19)).isTrue();
    }

    @Test
    void theConfigurationAcceptsFrequentSignalsOnlyInTheTestProfile() {
        MarketDataProperties properties = new MarketDataProperties(
                List.of("sim-a"),
                Duration.ofSeconds(30),
                new BigDecimal("5.0000"),
                new MarketDataProperties.Simulated(42, 60, Scenario.FREQUENT_SIGNALS));
        MarketDataConfig config = new MarketDataConfig();

        assertThatThrownBy(() ->
                        config.configuredSources(properties, clock, new MockEnvironment(), java.util.Optional.empty()))
                .hasMessageContaining("MARKET_SIMULATED_SCENARIO");
        MockEnvironment test = new MockEnvironment();
        test.setActiveProfiles("test");
        ConfiguredSources sources = config.configuredSources(properties, clock, test, java.util.Optional.empty());
        assertThat(sources.adapters()).hasSize(1);
        assertThat(new MarketDataProperties.Simulated(42, 60, null).scenario()).isEqualTo(Scenario.STANDARD);
    }
}
