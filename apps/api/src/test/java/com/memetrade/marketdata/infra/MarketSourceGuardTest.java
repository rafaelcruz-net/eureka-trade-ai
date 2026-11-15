package com.memetrade.marketdata.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.marketdata.api.Attribution;
import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.PriceVolumeWindow;
import com.memetrade.marketdata.api.ResolvedField;
import com.memetrade.marketdata.api.ResolvedFields;
import com.memetrade.marketdata.api.SourceKind;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.SourceStatus;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.api.UnknownReason;
import com.memetrade.marketdata.api.WatchedTokens;
import com.memetrade.marketdata.infra.coingecko.CoinGeckoProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Steps 9 and 10 (BR1.5, BR1.6, BR1.7, NFR1.5, team.md "REAL desligado por padrão"): the start-up lock against a
 * simulated source with REAL on, and the new reads of M4 ({@code activeSourceKind()}, {@code attribution()}, the
 * single source derived from {@link ResolvedField}) with the empty default {@link WatchedTokens}.
 */
class MarketSourceGuardTest {

    private static final MarketDataProperties.Simulated SIMULATED = new MarketDataProperties.Simulated(42, 200);

    private static MarketDataProperties sources(String... ids) {
        return new MarketDataProperties(List.of(ids), Duration.ofSeconds(30), new BigDecimal("5.0000"), SIMULATED);
    }

    private static MockEnvironment environment(boolean realEnabled, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("memetrade.real.enabled", Boolean.toString(realEnabled));
        environment.setActiveProfiles(profiles);
        return environment;
    }

    @Test
    void withRealOnOutsideTestAndLocalASimulatedSourceStopsTheStart() {
        assertThatThrownBy(() -> new MarketSourceGuard(sources("sim-a"), environment(true, "production")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MARKET_SOURCES")
                .hasMessageContaining("MEMETRADE_REAL_ENABLED");
        assertThatThrownBy(() -> new MarketSourceGuard(sources("coingecko", "sim-b"), environment(true, "worker")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theTestAndLocalProfilesMayRunRealWithTheSimulatedSources() {
        assertThatCode(() -> new MarketSourceGuard(sources("sim-a", "sim-b"), environment(true, "test")))
                .doesNotThrowAnyException();
        assertThatCode(() -> new MarketSourceGuard(sources("sim-a"), environment(true, "local", "worker")))
                .doesNotThrowAnyException();
    }

    @Test
    void withRealOffByDefaultOrWithTheRealSourceTheStartGoesOn() {
        assertThatCode(() -> new MarketSourceGuard(sources("sim-a", "sim-b"), new MockEnvironment()))
                .doesNotThrowAnyException();
        assertThatCode(() -> new MarketSourceGuard(sources("coingecko"), environment(true, "production")))
                .doesNotThrowAnyException();
    }

    @Test
    void theActiveSourceKindIsSimulatedAsSoonAsOneSimulatedSourceIsConfigured() {
        CoinGeckoProperties coingecko = CoinGeckoProperties.defaults();

        assertThat(query(sources("sim-a"), coingecko).activeSourceKind()).isEqualTo(SourceKind.SIMULATED);
        assertThat(query(sources("coingecko", "sim-b"), coingecko).activeSourceKind())
                .isEqualTo(SourceKind.SIMULATED);
        assertThat(query(sources("coingecko"), coingecko).activeSourceKind()).isEqualTo(SourceKind.REAL);
    }

    @Test
    void theAttributionComesOnlyWithTheRealSource() {
        CoinGeckoProperties coingecko = CoinGeckoProperties.defaults();

        assertThat(query(sources("coingecko"), coingecko).attribution())
                .contains(new Attribution("Dados: CoinGecko", "https://www.coingecko.com/"));
        assertThat(query(sources("sim-a", "sim-b"), coingecko).attribution()).isEmpty();
        assertThatThrownBy(() -> new Attribution("Dados", "http://insecure.example/"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theDefaultOfTheInterfaceDerivesTheKindFromTheSourcesFailClosed() {
        SourceRef real = new SourceRef("coingecko", "CoinGecko", false);
        SourceRef simulated = new SourceRef("sim-a", "Fonte simulada A", true);

        assertThat(statuses().activeSourceKind()).as("no source: fail-closed").isEqualTo(SourceKind.SIMULATED);
        assertThat(statuses(real).activeSourceKind()).isEqualTo(SourceKind.REAL);
        assertThat(statuses(real, simulated).activeSourceKind()).isEqualTo(SourceKind.SIMULATED);
        assertThat(statuses(real).attribution()).isEmpty();
    }

    @Test
    void theWatchedTokensAreEmptyInU2() {
        assertThat(WatchedTokens.NONE.watched()).isEmpty();
    }

    @Test
    void aSingleSourceIsDerivedFromTheResolvedFieldWithoutANewField() {
        SourceRef real = new SourceRef("coingecko", "CoinGecko", false);
        MarketValue.Known one = new MarketValue.Known(BigDecimal.ONE, real, Instant.EPOCH, false);
        MarketValue.Known other =
                new MarketValue.Known(BigDecimal.TEN, new SourceRef("other", "Other", false), Instant.EPOCH, false);

        assertThat(ResolvedFields.singleSource(ResolvedField.of(one))).isTrue();
        assertThat(ResolvedFields.singleSource(new ResolvedField(one, false, List.of(one, other))))
                .isFalse();
        assertThat(ResolvedFields.singleSource(new ResolvedField(one, true, List.of(one))))
                .isFalse();
        assertThat(ResolvedFields.singleSource(ResolvedField.of(MarketValue.Unknown.notProvided(real))))
                .isFalse();
    }

    @Test
    void withoutTheConfigurationTheProductionQueryFallsBackToTheStoredSources() {
        JdbcMarketDataQuery unconfigured =
                new JdbcMarketDataQuery(null, null, new com.memetrade.marketdata.domain.InMemoryMarketStore(), null);

        assertThat(unconfigured.activeSourceKind()).isEqualTo(SourceKind.SIMULATED);
        assertThat(unconfigured.attribution()).isEmpty();
    }

    private static JdbcMarketDataQuery query(MarketDataProperties sources, CoinGeckoProperties coingecko) {
        return new JdbcMarketDataQuery(null, null, null, null, sources, coingecko);
    }

    private static MarketDataQuery statuses(SourceRef... refs) {
        List<SourceStatus> statuses = java.util.Arrays.stream(refs)
                .map(ref -> new SourceStatus(ref, SourceStatus.State.OK, Optional.empty()))
                .toList();
        return new MarketDataQuery() {
            @Override
            public MarketValue referencePrice(TokenId token) {
                return new MarketValue.Unknown(UnknownReason.NOT_YET_OBSERVED, Optional.empty());
            }

            @Override
            public MarketValue liquidity(TokenId token) {
                return referencePrice(token);
            }

            @Override
            public MarketValue decimals(TokenId token) {
                return referencePrice(token);
            }

            @Override
            public PriceVolumeWindow window(TokenId token, Duration length) {
                return new PriceVolumeWindow(token, Instant.EPOCH, Instant.EPOCH, List.of());
            }

            @Override
            public List<SourceStatus> sourceStatuses() {
                return statuses;
            }
        };
    }
}
