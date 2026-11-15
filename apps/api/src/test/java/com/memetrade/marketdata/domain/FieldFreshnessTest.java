package com.memetrade.marketdata.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.SourceStatus.State;
import com.memetrade.marketdata.domain.FieldResolver.SourcedObservation;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Step 8 (BR1.4, entities.md FieldFreshness): stale by field, strictly above the limit. PRICE, LIQUIDITY and VOLUME_24H
 * keep 5 minutes; HOLDERS and CREATED_AT get 2 hours. {@code ResolvedField} and {@code MarketValue} do not change.
 */
class FieldFreshnessTest {

    private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");
    private static final SourceRef REAL = new SourceRef("coingecko", "CoinGecko", false);

    @Test
    void thePriceFieldsAreStaleStrictlyAfterFiveMinutes() {
        for (MarketField field : List.of(MarketField.PRICE, MarketField.LIQUIDITY, MarketField.VOLUME_24H)) {
            assertThat(Freshness.isStale(field, NOW.minusSeconds(300), NOW))
                    .as(field.name())
                    .isFalse();
            assertThat(Freshness.isStale(field, NOW.minusSeconds(301), NOW))
                    .as(field.name())
                    .isTrue();
        }
        assertThat(Freshness.isStale(NOW.minusSeconds(301), NOW))
                .as("the rule without a field")
                .isTrue();
    }

    @Test
    void holdersAndCreationAreStaleStrictlyAfterTwoHours() {
        for (MarketField field : List.of(MarketField.HOLDERS, MarketField.CREATED_AT)) {
            assertThat(Freshness.isStale(field, NOW.minus(Duration.ofMinutes(90)), NOW))
                    .as(field.name())
                    .isFalse();
            assertThat(Freshness.isStale(field, NOW.minusSeconds(7_200), NOW))
                    .as(field.name())
                    .isFalse();
            assertThat(Freshness.isStale(field, NOW.minusSeconds(7_201), NOW))
                    .as(field.name())
                    .isTrue();
        }
    }

    @Test
    void theResolverUsesTheLimitOfTheField() {
        FieldResolver resolver = new FieldResolver(DivergencePolicy.uniform(new BigDecimal("5")));
        SourcedObservation holders =
                new SourcedObservation(REAL, State.OK, new BigDecimal("1500"), null, NOW.minus(Duration.ofMinutes(90)));
        SourcedObservation price =
                new SourcedObservation(REAL, State.OK, new BigDecimal("1.5"), null, NOW.minus(Duration.ofMinutes(6)));

        assertThat(((MarketValue.Known) resolver.resolve(MarketField.HOLDERS, List.of(holders), NOW)
                                .value())
                        .stale())
                .isFalse();
        assertThat(((MarketValue.Known) resolver.resolve(MarketField.PRICE, List.of(price), NOW)
                                .value())
                        .stale())
                .isTrue();
    }

    @Test
    void aStoredValueIsReadWithTheLimitOfItsField() {
        Instant observed = NOW.minus(Duration.ofMinutes(30));

        MarketValue holders = ObservationValues.fromStored(
                MarketField.HOLDERS, "KNOWN", new BigDecimal("10"), null, REAL, observed, NOW);
        MarketValue price = ObservationValues.fromStored("KNOWN", new BigDecimal("10"), null, REAL, observed, NOW);

        assertThat(((MarketValue.Known) holders).stale()).isFalse();
        assertThat(((MarketValue.Known) price).stale()).isTrue();
    }

    @Test
    void everyFieldHasItsLimit() {
        assertThat(Freshness.staleAfter(MarketField.PRICE)).isEqualTo(Duration.ofMinutes(5));
        assertThat(Freshness.staleAfter(MarketField.LIQUIDITY)).isEqualTo(Duration.ofMinutes(5));
        assertThat(Freshness.staleAfter(MarketField.VOLUME_24H)).isEqualTo(Duration.ofMinutes(5));
        assertThat(Freshness.staleAfter(MarketField.HOLDERS)).isEqualTo(Duration.ofHours(2));
        assertThat(Freshness.staleAfter(MarketField.CREATED_AT)).isEqualTo(Duration.ofHours(2));
    }
}
