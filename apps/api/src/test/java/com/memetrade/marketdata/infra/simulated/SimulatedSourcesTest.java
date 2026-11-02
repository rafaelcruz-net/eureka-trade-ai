package com.memetrade.marketdata.infra.simulated;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketSourceAdapter.FetchRequest;
import com.memetrade.marketdata.api.MarketSourceAdapter.FetchResult;
import com.memetrade.marketdata.api.MarketSourceAdapter.Observation;
import com.memetrade.marketdata.api.MarketSourceAdapter.TokenInfo;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.domain.DivergencePolicy;
import com.memetrade.marketdata.domain.ObservationValidator;
import com.memetrade.testsupport.MutableClock;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The two simulated sources of U4 (US2.4, BR1.6, BR1.9, NFR6.6). */
@Tag("u4")
class SimulatedSourcesTest {

    private final MutableClock clock = new MutableClock();
    private final SimulatedMarketSource a = SimulatedMarketSource.forSource("sim-a", 42, 200, clock);
    private final SimulatedMarketSource b = SimulatedMarketSource.forSource("sim-b", 42, 200, clock);

    @Test
    void bothSourcesAreSimulatedAndShareExactlyFiftyTokensByChainAndMint() {
        Set<TokenId> inA = ids(fetch(a));
        Set<TokenId> inB = ids(fetch(b));

        assertThat(a.source().simulated()).isTrue();
        assertThat(b.source().simulated()).isTrue();
        assertThat(inA).hasSize(200);
        assertThat(inA.stream().filter(inB::contains).count()).isEqualTo(50);
    }

    @Test
    void theSameSymbolWithDifferentMintsIsTwoTokens() {
        Map<String, Long> pepe = fetch(a).tokens().stream()
                .filter(t -> t.symbol().equals("PEPE"))
                .collect(Collectors.groupingBy(t -> t.token().mint(), Collectors.counting()));

        assertThat(pepe.size()).isGreaterThanOrEqualTo(2);
        assertThat(pepe.values()).allMatch(count -> count == 1);
    }

    @Test
    void sourceBDivergesBeyondTheToleranceOnlyWhereTheSeedSaysSo() {
        FetchResult resultA = fetch(a);
        FetchResult resultB = fetch(b);
        DivergencePolicy policy = DivergencePolicy.uniform(new BigDecimal("5.0000"));
        TokenId divergent = resultA.tokens().get(3).token();
        TokenId close = resultA.tokens().get(6).token();
        TokenId equal = resultA.tokens().get(5).token();

        assertThat(policy.diverges(
                        MarketField.PRICE,
                        value(resultA, divergent, MarketField.PRICE),
                        value(resultB, divergent, MarketField.PRICE)))
                .isTrue();
        assertThat(policy.diverges(
                        MarketField.LIQUIDITY,
                        value(resultA, close, MarketField.LIQUIDITY),
                        value(resultB, close, MarketField.LIQUIDITY)))
                .isFalse();
        assertThat(value(resultA, equal, MarketField.PRICE))
                .isEqualByComparingTo(value(resultB, equal, MarketField.PRICE));
    }

    @Test
    void sourceBLeavesOutHoldersOfSomeCommonTokensAsUnknownNeverZero() {
        FetchResult resultB = fetch(b);
        TokenId seventh = resultB.tokens().get(7).token();

        MarketValue holders = observation(resultB, seventh, MarketField.HOLDERS).value();
        assertThat(holders).isInstanceOf(MarketValue.Unknown.class);
    }

    @Test
    void theSameSeedAndTimeGiveTheSameCollectionInBothSources() {
        assertThat(fetch(SimulatedMarketSource.forSource("sim-b", 42, 200, clock)))
                .isEqualTo(fetch(b));
    }

    @Test
    void everySimulatedCollectionPassesThePortValidation() {
        ObservationValidator.Validated validated = ObservationValidator.validate(fetch(b), b.source());

        assertThat(validated.tokens()).hasSize(200);
        assertThat(validated.discardedTotal()).isZero();
    }

    @Test
    void anUnknownSourceIdIsRefused() {
        assertThatThrownBy(() -> SimulatedMarketSource.forSource("real-dex", 42, 200, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** NFR6.6: the simulated collection can never open a socket; no class of the adapter reaches the network API. */
    @Test
    void theSimulatedAdaptersCannotOpenASocket() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("java.net..", "java.nio.channels..", "javax.net..")
                .check(new ClassFileImporter()
                        .importClasses(
                                SimulatedMarketSource.class, SyntheticMintEncoder.class, ObservationValidator.class));
    }

    private FetchResult fetch(SimulatedMarketSource source) {
        return source.fetch(new FetchRequest(clock.instant()));
    }

    private static Set<TokenId> ids(FetchResult result) {
        return result.tokens().stream().map(TokenInfo::token).collect(Collectors.toSet());
    }

    private static Observation observation(FetchResult result, TokenId token, MarketField field) {
        return result.observations().stream()
                .filter(o -> o.token().equals(token) && o.field() == field)
                .findFirst()
                .orElseThrow();
    }

    private static BigDecimal value(FetchResult result, TokenId token, MarketField field) {
        return ((MarketValue.Known) observation(result, token, field).value()).value();
    }

    @SuppressWarnings("unused")
    private static <T> Function<T, T> identity() {
        return Function.identity();
    }
}
