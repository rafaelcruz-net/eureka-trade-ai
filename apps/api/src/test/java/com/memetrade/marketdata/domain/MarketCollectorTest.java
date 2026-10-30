package com.memetrade.marketdata.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketSourceAdapter;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.SourceStatus;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.api.UnknownReason;
import com.memetrade.testsupport.MutableClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("u1")
class MarketCollectorTest {

    private static final SourceRef SOURCE = new SourceRef("fixture", "Fixture", true);
    private static final TokenId TOKEN = new TokenId("solana", "FixtureMint1111111111111111111111111111111");

    private final MutableClock clock = new MutableClock();
    private final InMemoryMarketStore store = new InMemoryMarketStore();
    private final AtomicBoolean failing = new AtomicBoolean();
    private final MarketCollector collector = new MarketCollector(new FixtureAdapter(), store, clock);

    @Test
    void aSuccessfulCollectionStoresEveryFieldAndMarksTheSourceOk() {
        assertThat(collector.collect()).isTrue();

        assertThat(store.sources.get("fixture").status()).isEqualTo(SourceStatus.State.OK);
        assertThat(store.sources.get("fixture").lastUpdatedAt()).contains(clock.instant());
        for (MarketField field : MarketField.values()) {
            assertThat(store.observation(TOKEN, field)).as(field.name()).isNotNull();
        }
    }

    @Test
    void theFirstCollectionOnAnEmptyStoreRegistersTheSourceBeforeItsObservations() {
        // Regression: observations were written before the data_source row, violating the foreign key.
        InMemoryMarketStore empty = new InMemoryMarketStore();

        assertThat(new MarketCollector(new FixtureAdapter(), empty, clock).collect())
                .isTrue();
        assertThat(empty.sources).containsKey("fixture");
        assertThat(empty.observation(TOKEN, MarketField.PRICE)).isNotNull();
    }

    @Test
    void aFieldTheSourceDidNotSendBecomesUnknownNotProvidedNeverZero() {
        collector.collect();

        MarketValue holders = store.observation(TOKEN, MarketField.HOLDERS).value();
        assertThat(holders).isEqualTo(new MarketValue.Unknown(UnknownReason.NOT_PROVIDED, Optional.of(SOURCE)));
    }

    @Test
    void anInformedZeroIsKnownZero() {
        collector.collect();

        MarketValue liquidity = store.observation(TOKEN, MarketField.LIQUIDITY).value();
        assertThat(liquidity).isInstanceOf(MarketValue.Known.class);
        assertThat(((MarketValue.Known) liquidity).value()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void theCreationObservationSetsTheListingOrderTime() {
        collector.collect();

        assertThat(store.creation.get(TOKEN)).contains(Instant.ofEpochSecond(1_700_000_000L));
    }

    @Test
    void aFailureMarksTheSourceFailedAndKeepsPreviousValuesAndTimes() {
        collector.collect();
        Instant firstCollection = clock.instant();
        clock.advance(Duration.ofSeconds(15));
        failing.set(true);

        assertThat(collector.collect()).isFalse();

        SourceStatus status = store.sources.get("fixture");
        assertThat(status.status()).isEqualTo(SourceStatus.State.FAILED);
        assertThat(status.lastUpdatedAt()).contains(firstCollection);
        assertThat(store.observation(TOKEN, MarketField.PRICE).observedAt()).isEqualTo(firstCollection);
    }

    @Test
    void theStaleMarkIsStrictlyAboveFiveMinutes() {
        Instant observed = clock.instant();

        assertThat(Freshness.isStale(observed, observed.plus(Duration.ofMinutes(5))))
                .isFalse();
        assertThat(Freshness.isStale(
                        observed, observed.plus(Duration.ofMinutes(5)).plusMillis(1)))
                .isTrue();
        assertThat(Freshness.isStale(observed, observed)).isFalse();
    }

    private final class FixtureAdapter implements MarketSourceAdapter {
        @Override
        public SourceRef source() {
            return SOURCE;
        }

        @Override
        public FetchResult fetch(FetchRequest request) {
            if (failing.get()) {
                throw new IllegalStateException("fixture source down");
            }
            Instant at = request.requestedAt();
            return new FetchResult(
                    List.of(new TokenInfo(TOKEN, "FIX", "Fixture", 6)),
                    List.of(
                            new Observation(
                                    TOKEN,
                                    MarketField.PRICE,
                                    new MarketValue.Known(new BigDecimal("0.000123"), SOURCE, at, false)),
                            new Observation(
                                    TOKEN,
                                    MarketField.LIQUIDITY,
                                    new MarketValue.Known(BigDecimal.ZERO, SOURCE, at, false)),
                            new Observation(
                                    TOKEN,
                                    MarketField.VOLUME_24H,
                                    new MarketValue.Known(new BigDecimal("10.50"), SOURCE, at, false)),
                            new Observation(
                                    TOKEN,
                                    MarketField.CREATED_AT,
                                    new MarketValue.Known(new BigDecimal("1700000000"), SOURCE, at, false))));
        }
    }
}
