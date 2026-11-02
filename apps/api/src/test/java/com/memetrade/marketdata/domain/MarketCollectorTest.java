package com.memetrade.marketdata.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.marketdata.api.FailureCategory;
import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketSourceAdapter;
import com.memetrade.marketdata.api.MarketSourceAdapter.FetchResult;
import com.memetrade.marketdata.api.MarketSourceAdapter.TokenInfo;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.SourceStatus.State;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.api.UnknownReason;
import com.memetrade.marketdata.domain.SourceFetcher.Failed;
import com.memetrade.marketdata.domain.SourceFetcher.FetchOutcome;
import com.memetrade.marketdata.domain.SourceFetcher.Fetched;
import com.memetrade.testsupport.MutableClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** F1 with an in-memory store and a scripted fetcher (U1 behaviour kept, U4 states and mark added). */
@Tag("u1")
class MarketCollectorTest {

    private static final SourceRef SOURCE = new SourceRef("fixture", "Fixture", true);
    private static final TokenId TOKEN = new TokenId("solana", "FixtureMint111111111111111111111111111111111");

    private final MutableClock clock = new MutableClock();
    private final InMemoryMarketStore store = new InMemoryMarketStore();
    private final AtomicReference<FetchOutcome> scripted = new AtomicReference<>();
    private final FixtureAdapter adapter = new FixtureAdapter();
    private final MarketCollector collector = new MarketCollector(
            store,
            (source, request) -> scripted.get() != null
                    ? scripted.getAndSet(null)
                    : new Fetched(source.fetch(request), 0, Optional.empty()),
            UnitOfWork.DIRECT,
            clock);

    @BeforeEach
    void register() {
        store.registerSource(SOURCE, Duration.ofSeconds(30));
    }

    @Test
    void aSuccessfulCollectionStoresEveryFieldAndMarksTheSourceOk() {
        CollectionOutcome outcome = collector.collect(adapter);

        assertThat(outcome.status()).isEqualTo(State.OK);
        assertThat(store.sources.get("fixture").lastUpdatedAt()).contains(clock.instant());
        for (MarketField field : MarketField.values()) {
            assertThat(store.observation(TOKEN, "fixture", field))
                    .as(field.name())
                    .isNotNull();
        }
    }

    @Test
    void aFieldTheSourceDidNotSendBecomesUnknownNotProvidedNeverZero() {
        collector.collect(adapter);

        assertThat(store.observation(TOKEN, "fixture", MarketField.HOLDERS).value())
                .isEqualTo(new MarketValue.Unknown(UnknownReason.NOT_PROVIDED, Optional.of(SOURCE)));
    }

    @Test
    void anInformedZeroIsKnownZero() {
        collector.collect(adapter);

        MarketValue liquidity =
                store.observation(TOKEN, "fixture", MarketField.LIQUIDITY).value();
        assertThat(((MarketValue.Known) liquidity).value()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void theCreationObservationSetsTheCreationTime() {
        collector.collect(adapter);

        assertThat(store.creation.get(TOKEN)).contains(Instant.ofEpochSecond(1_700_000_000L));
    }

    @Test
    void aFailureMarksTheSourceFailedAndKeepsPreviousValuesAndTimes() {
        collector.collect(adapter);
        Instant firstCollection = clock.instant();
        clock.advance(Duration.ofSeconds(30));
        scripted.set(new Failed(FailureCategory.TIMEOUT, "TimeoutException", 2));

        CollectionOutcome outcome = collector.collect(adapter);

        assertThat(outcome.status()).isEqualTo(State.FAILED);
        assertThat(outcome.exceptionClass()).contains("TimeoutException");
        SourceState state = store.sources.get("fixture");
        assertThat(state.failureCategory()).contains(FailureCategory.TIMEOUT);
        assertThat(state.lastUpdatedAt()).contains(firstCollection);
        assertThat(state.lastAttemptAt()).contains(clock.instant());
        assertThat(store.observation(TOKEN, "fixture", MarketField.PRICE).observedAt())
                .isEqualTo(firstCollection);
    }

    @Test
    void theStaleMarkIsStrictlyAboveFiveMinutes() {
        Instant observed = clock.instant();

        assertThat(Freshness.isStale(observed, observed.plus(Duration.ofMinutes(5))))
                .isFalse();
        assertThat(Freshness.isStale(
                        observed, observed.plus(Duration.ofMinutes(5)).plusMillis(1)))
                .isTrue();
    }

    @Test
    @Tag("u4")
    void theMarkGrowsOnlyWhenTheStateChanges() {
        collector.collect(adapter);
        long afterFirst = store.mark;
        clock.advance(Duration.ofSeconds(30));
        collector.collect(adapter);

        assertThat(afterFirst).isEqualTo(1);
        assertThat(store.mark).as("OK -> OK changes nothing").isEqualTo(1);

        scripted.set(new Failed(FailureCategory.ERROR_RESPONSE, "MarketSourceException", 2));
        collector.collect(adapter);
        assertThat(store.mark).isEqualTo(2);
    }

    @Test
    @Tag("u4")
    void aCollectionAfterANewAttemptIsDegradedWithTheCauseAndBackToOkAfterwards() {
        scripted.set(new Fetched(
                adapter.fetch(new MarketSourceAdapter.FetchRequest(clock.instant())),
                1,
                Optional.of(FailureCategory.TIMEOUT)));

        CollectionOutcome degraded = collector.collect(adapter);
        CollectionOutcome ok = collector.collect(adapter);

        assertThat(degraded.status()).isEqualTo(State.DEGRADED);
        assertThat(degraded.category()).contains(FailureCategory.TIMEOUT);
        assertThat(ok.status()).isEqualTo(State.OK);
        assertThat(store.sources.get("fixture").failureCategory()).isEmpty();
    }

    @Test
    @Tag("u4")
    void aDiscardedTokenMakesTheSourceDegradedWithInvalidPayloadAndKeepsTheOthers() {
        adapter.extraInvalidToken = true;

        CollectionOutcome outcome = collector.collect(adapter);

        assertThat(outcome.status()).isEqualTo(State.DEGRADED);
        assertThat(outcome.category()).contains(FailureCategory.INVALID_PAYLOAD);
        assertThat(outcome.discarded()).containsEntry(ObservationValidator.DiscardReason.INVALID_FIELD, 1);
        assertThat(store.tokens).containsOnlyKeys(TOKEN);
    }

    @Test
    @Tag("u4")
    void aPendingSourceFailingGoesStraightToFailed() {
        scripted.set(new Failed(FailureCategory.INVALID_PAYLOAD, "MarketSourceException", 0));

        CollectionOutcome outcome = collector.collect(adapter);

        assertThat(outcome.previous()).isEqualTo(State.PENDING);
        assertThat(outcome.status()).isEqualTo(State.FAILED);
        assertThat(store.sources.get("fixture").lastUpdatedAt()).isEmpty();
        assertThat(store.observations).isEmpty();
    }

    @Test
    @Tag("u4")
    void oneSamplePerFieldAndFiveMinuteBucketButTheCurrentValueFollowsEveryCollection() {
        collector.collect(adapter);
        clock.advance(Duration.ofSeconds(30));
        collector.collect(adapter);
        clock.advance(Duration.ofMinutes(5));
        collector.collect(adapter);

        assertThat(store.samples.keySet())
                .filteredOn(k -> k.contains("/PRICE/"))
                .hasSize(2);
        assertThat(store.samples.keySet()).noneMatch(k -> k.contains("CREATED_AT"));
        assertThat(store.observation(TOKEN, "fixture", MarketField.PRICE).observedAt())
                .isEqualTo(clock.instant());
    }

    @Test
    @Tag("u4")
    void anUnregisteredSourceIsRefused() {
        MarketCollector unregistered = new MarketCollector(
                new InMemoryMarketStore(),
                (source, request) -> new Fetched(source.fetch(request), 0, Optional.empty()),
                UnitOfWork.DIRECT,
                clock);

        assertThatThrownBy(() -> unregistered.collect(adapter)).isInstanceOf(IllegalStateException.class);
    }

    private final class FixtureAdapter implements MarketSourceAdapter {

        boolean extraInvalidToken;

        @Override
        public SourceRef source() {
            return SOURCE;
        }

        @Override
        public FetchResult fetch(FetchRequest request) {
            Instant at = request.requestedAt();
            List<TokenInfo> tokens = new ArrayList<>(List.of(new TokenInfo(TOKEN, "FIX", "Fixture", 6)));
            if (extraInvalidToken) {
                tokens.add(new TokenInfo(new TokenId("solana", "not-base58!"), "BAD", null, 6));
            }
            return new FetchResult(
                    tokens,
                    List.of(
                            observation(MarketField.PRICE, "0.000123", at),
                            observation(MarketField.LIQUIDITY, "0", at),
                            observation(MarketField.VOLUME_24H, "10.50", at),
                            observation(MarketField.CREATED_AT, "1700000000", at)));
        }

        private Observation observation(MarketField field, String value, Instant at) {
            return new Observation(TOKEN, field, new MarketValue.Known(new BigDecimal(value), SOURCE, at, false));
        }
    }
}
