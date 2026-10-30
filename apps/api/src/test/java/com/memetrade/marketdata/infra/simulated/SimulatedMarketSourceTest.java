package com.memetrade.marketdata.infra.simulated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketSourceAdapter.FetchRequest;
import com.memetrade.marketdata.api.MarketSourceAdapter.FetchResult;
import com.memetrade.marketdata.api.MarketSourceAdapter.Observation;
import com.memetrade.marketdata.api.MarketSourceAdapter.TokenInfo;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.UnknownReason;
import com.memetrade.testsupport.MutableClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u1")
class SimulatedMarketSourceTest {

    private final MutableClock clock = new MutableClock();

    @Test
    void theSameSeedAndClockProduceTheSameCollections() {
        FetchResult a = new SimulatedMarketSource(42, 30, clock).fetch(new FetchRequest(clock.instant()));
        FetchResult b = new SimulatedMarketSource(42, 30, clock).fetch(new FetchRequest(clock.instant()));
        FetchResult other = new SimulatedMarketSource(7, 30, clock).fetch(new FetchRequest(clock.instant()));

        assertThat(a).isEqualTo(b);
        assertThat(other.tokens()).isNotEqualTo(a.tokens());
    }

    @Test
    void everyCollectionHasATokenWithoutHoldersAndOneWithLiquidityInformedAsZero() {
        SimulatedMarketSource source = new SimulatedMarketSource(42, 30, clock);
        for (int collection = 0; collection < 5; collection++) {
            clock.advance(Duration.ofSeconds(15));
            FetchResult result = source.fetch(new FetchRequest(clock.instant()));

            assertThat(result.observations())
                    .anySatisfy(o -> {
                        assertThat(o.field()).isEqualTo(MarketField.HOLDERS);
                        assertThat(o.value())
                                .isEqualTo(new MarketValue.Unknown(
                                        UnknownReason.NOT_PROVIDED,
                                        java.util.Optional.of(SimulatedMarketSource.SOURCE)));
                    })
                    .anySatisfy(o -> {
                        assertThat(o.field()).isEqualTo(MarketField.LIQUIDITY);
                        assertThat(((MarketValue.Known) o.value()).value()).isEqualByComparingTo(BigDecimal.ZERO);
                    });
        }
    }

    @Test
    void repeatedSymbolsHaveDifferentMints() {
        List<TokenInfo> tokens = new SimulatedMarketSource(42, 30, clock)
                .fetch(new FetchRequest(clock.instant()))
                .tokens();
        Map<String, List<String>> mintsBySymbol = tokens.stream()
                .collect(Collectors.groupingBy(
                        TokenInfo::symbol, Collectors.mapping(t -> t.token().mint(), Collectors.toList())));

        assertThat(mintsBySymbol.get("PEPE")).hasSizeGreaterThanOrEqualTo(2).doesNotHaveDuplicates();
        assertThat(tokens).extracting(t -> t.token().mint()).doesNotHaveDuplicates();
        assertThat(tokens).allSatisfy(t -> assertThat(t.token().mint()).matches("[1-9A-HJ-NP-Za-km-z]{32,44}"));
    }

    @Test
    void theSourceIsMarkedSimulatedAndValuesAreExactDecimals() {
        SimulatedMarketSource source = new SimulatedMarketSource(42, 30, clock);
        FetchResult result = source.fetch(new FetchRequest(clock.instant()));

        assertThat(source.source().simulated()).isTrue();
        assertThat(result.observations())
                .map(Observation::value)
                .filteredOn(MarketValue::isKnown)
                .allSatisfy(v -> assertThat(((MarketValue.Known) v).value()).isInstanceOf(BigDecimal.class));
    }

    @Test
    void oneTokenHasNoCreationTimeSoItsAgeIsUnknown() {
        FetchResult result = new SimulatedMarketSource(42, 30, clock).fetch(new FetchRequest(clock.instant()));

        long withCreation = result.observations().stream()
                .filter(o -> o.field() == MarketField.CREATED_AT)
                .count();
        assertThat(withCreation).isEqualTo(result.tokens().size() - 1);
    }

    @Test
    void valuesChangeBetweenCollectionsButTokensStayTheSame() {
        SimulatedMarketSource source = new SimulatedMarketSource(42, 30, clock);
        FetchResult first = source.fetch(new FetchRequest(clock.instant()));
        FetchResult second = source.fetch(new FetchRequest(clock.instant().plusSeconds(15)));

        assertThat(second.tokens()).isEqualTo(first.tokens());
        assertThat(second.observations()).isNotEqualTo(first.observations());
    }

    @Test
    void tooFewTokensAreRefused() {
        assertThatThrownBy(() -> new SimulatedMarketSource(42, 4, clock)).isInstanceOf(IllegalArgumentException.class);
    }
}
