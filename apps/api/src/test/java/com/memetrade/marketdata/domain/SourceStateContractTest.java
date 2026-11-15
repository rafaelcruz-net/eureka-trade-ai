package com.memetrade.marketdata.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.marketdata.api.FailureCategory;
import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketSourceAdapter;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.SourceStatus.State;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.api.UnknownReason;
import com.memetrade.marketdata.domain.FieldResolver.SourcedObservation;
import com.memetrade.marketdata.domain.SourceFetcher.Failed;
import com.memetrade.marketdata.domain.SourceFetcher.Fetched;
import com.memetrade.testsupport.MutableClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Step 7 (BR1.9, NFR4.3, infrastructure-specification.md "Teste do mapa de estados"): the contract of the source states
 * in the collector. For the tolerant source (U2): OK, partial round DEGRADED, first round without price DEGRADED,
 * second one FAILED, then OK again; the same through the exception path. The simulated sources keep their behaviour.
 */
class SourceStateContractTest {

    private static final SourceRef REAL = new SourceRef("coingecko", "CoinGecko", false);
    private static final SourceRef SIMULATED = new SourceRef("sim-a", "Fonte simulada A", true);
    /** Synthetic mints (Base58, never a real token). */
    private static final TokenId A = new TokenId("solana", "Synth" + "A".repeat(35));

    private static final TokenId B = new TokenId("solana", "Synth" + "B".repeat(35));

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-14T12:00:00Z"));
    private final InMemoryMarketStore store = new InMemoryMarketStore();
    private final List<MarketSourceAdapter.FetchRequest> requests = new ArrayList<>();
    private final Deque<Supplier<SourceFetcher.FetchOutcome>> script = new ArrayDeque<>();
    private final MarketCollector collector = new MarketCollector(
            store, (adapter, request) -> next(adapter, request), UnitOfWork.DIRECT, clock, () -> Set.of(B));

    @BeforeEach
    void register() {
        store.registerSource(REAL, Duration.ofSeconds(30));
        store.registerSource(SIMULATED, Duration.ofSeconds(30));
    }

    private SourceFetcher.FetchOutcome next(MarketSourceAdapter adapter, MarketSourceAdapter.FetchRequest request) {
        requests.add(request);
        return script.removeFirst().get();
    }

    private void round(int active, int priced, Optional<FailureCategory> cut, MarketSourceAdapter.Observation... obs) {
        List<MarketSourceAdapter.TokenInfo> tokens = List.of(
                new MarketSourceAdapter.TokenInfo(A, "AAA", "Token A", 6),
                new MarketSourceAdapter.TokenInfo(B, "BBB", "Token B", 6));
        script.add(() -> new Fetched(
                new MarketSourceAdapter.FetchResult(
                        tokens, List.of(obs), Optional.of(new MarketSourceAdapter.RoundSummary(active, priced, cut))),
                0,
                Optional.empty()));
    }

    private void failure(FailureCategory category) {
        script.add(() -> new Failed(category, "CoinGeckoCallException", 0));
    }

    private State collect(MarketSourceAdapter adapter) {
        State state = collector.collect(adapter).status();
        clock.advance(Duration.ofSeconds(30));
        return state;
    }

    private static MarketSourceAdapter.Observation price(TokenId token, String value, Instant at) {
        return new MarketSourceAdapter.Observation(
                token, MarketField.PRICE, new MarketValue.Known(new BigDecimal(value), REAL, at, false));
    }

    @Test
    void theTolerantMapGoesOkPartialNoPriceNoPriceAndBackToOk() {
        Instant t0 = clock.instant();
        round(2, 2, Optional.empty(), price(A, "1", t0), price(B, "2", t0));
        round(2, 1, Optional.empty(), price(A, "1", t0.plusSeconds(30)));
        round(2, 0, Optional.of(FailureCategory.ERROR_RESPONSE));
        round(2, 0, Optional.of(FailureCategory.TIMEOUT));
        round(2, 2, Optional.empty(), price(A, "1", t0), price(B, "2", t0));

        assertThat(List.of(
                        collect(TOLERANT), collect(TOLERANT), collect(TOLERANT), collect(TOLERANT), collect(TOLERANT)))
                .containsExactly(State.OK, State.DEGRADED, State.DEGRADED, State.FAILED, State.OK);
    }

    @Test
    void theExceptionPathIsTolerantOnceAndKeepsTheLastSuccessTime() {
        Instant t0 = clock.instant();
        round(2, 2, Optional.empty(), price(A, "1", t0), price(B, "2", t0));
        failure(FailureCategory.TIMEOUT);
        failure(FailureCategory.ERROR_RESPONSE);

        assertThat(collect(TOLERANT)).isEqualTo(State.OK);
        assertThat(collect(TOLERANT)).isEqualTo(State.DEGRADED);
        assertThat(store.sources.get("coingecko").lastUpdatedAt()).contains(t0);
        assertThat(store.sources.get("coingecko").failureCategory()).contains(FailureCategory.TIMEOUT);
        assertThat(collect(TOLERANT)).isEqualTo(State.FAILED);
    }

    @Test
    void aRoundWithoutPriceAndAFailureInARowCountTogether() {
        round(2, 0, Optional.of(FailureCategory.ERROR_RESPONSE));
        failure(FailureCategory.ERROR_RESPONSE);

        assertThat(collect(TOLERANT)).isEqualTo(State.DEGRADED);
        assertThat(collect(TOLERANT)).isEqualTo(State.FAILED);
    }

    @Test
    void aCutRoundNamesItsCauseWithTheExistingCategories() {
        Instant t0 = clock.instant();
        round(2, 1, Optional.of(FailureCategory.TIMEOUT), price(A, "1", t0));
        round(2, 1, Optional.empty(), price(A, "1", t0));

        assertThat(collect(TOLERANT)).isEqualTo(State.DEGRADED);
        assertThat(store.sources.get("coingecko").failureCategory()).contains(FailureCategory.TIMEOUT);
        assertThat(collect(TOLERANT)).isEqualTo(State.DEGRADED);
        assertThat(store.sources.get("coingecko").failureCategory()).contains(FailureCategory.INVALID_PAYLOAD);
    }

    @Test
    void onlyTheRenewedFieldsAreWrittenAndTheOthersKeepTheirObservationAndCreation() {
        Instant t0 = clock.instant();
        MarketSourceAdapter.Observation holders = new MarketSourceAdapter.Observation(
                A, MarketField.HOLDERS, new MarketValue.Known(new BigDecimal("1500"), REAL, t0, false));
        MarketSourceAdapter.Observation created = new MarketSourceAdapter.Observation(
                A, MarketField.CREATED_AT, new MarketValue.Known(BigDecimal.valueOf(1_700_000_000L), REAL, t0, false));
        round(1, 1, Optional.empty(), price(A, "1", t0), holders, created);
        round(1, 1, Optional.empty(), price(A, "2", t0.plusSeconds(30)));

        collect(TOLERANT);
        collect(TOLERANT);

        assertThat(store.observation(A, "coingecko", MarketField.HOLDERS).observedAt())
                .isEqualTo(t0);
        assertThat(store.observation(A, "coingecko", MarketField.PRICE).observedAt())
                .isEqualTo(t0.plusSeconds(30));
        assertThat(store.observation(B, "coingecko", MarketField.PRICE))
                .as("never sent, never written")
                .isNull();
        assertThat(store.creation.get(A)).contains(Instant.ofEpochSecond(1_700_000_000L));
    }

    @Test
    void aFailedRealSourceMakesTheFieldUnknownSourceUnavailableNeverZero() {
        FieldResolver resolver = new FieldResolver(DivergencePolicy.uniform(new BigDecimal("5")));
        SourcedObservation failed = new SourcedObservation(
                REAL, State.FAILED, new BigDecimal("1.5"), null, clock.instant().minusSeconds(40));

        assertThat(resolver.resolve(MarketField.PRICE, List.of(failed), clock.instant())
                        .value())
                .isEqualTo(new MarketValue.Unknown(UnknownReason.SOURCE_UNAVAILABLE, Optional.empty()));
    }

    @Test
    void theSimulatedSourcesKeepFailingAtOnceAndWritingEveryField() {
        failure(FailureCategory.ERROR_RESPONSE);
        script.add(() -> new Fetched(SIMULATED_ADAPTER.fetch(null), 0, Optional.empty()));

        assertThat(collect(SIMULATED_ADAPTER)).isEqualTo(State.FAILED);
        assertThat(collect(SIMULATED_ADAPTER)).isEqualTo(State.OK);
        for (MarketField field : MarketField.values()) {
            assertThat(store.observation(A, "sim-a", field)).as(field.name()).isNotNull();
        }
    }

    @Test
    void theWatchedTokensGoOnlyToTheTolerantSource() {
        round(1, 1, Optional.empty(), price(A, "1", clock.instant()));
        script.add(() -> new Fetched(SIMULATED_ADAPTER.fetch(null), 0, Optional.empty()));

        collect(TOLERANT);
        collect(SIMULATED_ADAPTER);

        assertThat(requests.get(0).watched()).containsExactly(B);
        assertThat(requests.get(1).watched()).isEmpty();
    }

    /** A tolerant source whose answers come from the script of the fetcher. */
    private static final MarketSourceAdapter TOLERANT = new MarketSourceAdapter() {
        @Override
        public SourceRef source() {
            return REAL;
        }

        @Override
        public FetchResult fetch(FetchRequest request) {
            throw new UnsupportedOperationException("answered by the scripted fetcher");
        }

        @Override
        public boolean tolerantFailure() {
            return true;
        }
    };

    /** A simulated source that sends only PRICE for token A (the collector fills the rest with NOT_PROVIDED). */
    private static final MarketSourceAdapter SIMULATED_ADAPTER = new MarketSourceAdapter() {
        @Override
        public SourceRef source() {
            return SIMULATED;
        }

        @Override
        public FetchResult fetch(FetchRequest request) {
            return new FetchResult(
                    List.of(new TokenInfo(A, "AAA", "Token A", 6)),
                    List.of(new Observation(
                            A,
                            MarketField.PRICE,
                            new MarketValue.Known(
                                    BigDecimal.ONE, SIMULATED, Instant.parse("2026-11-14T12:00:00Z"), false))));
        }
    };
}
