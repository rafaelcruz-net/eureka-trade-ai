package com.memetrade.marketdata.domain;

import com.memetrade.marketdata.api.FailureCategory;
import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketSourceAdapter;
import com.memetrade.marketdata.api.MarketSourceAdapter.FetchRequest;
import com.memetrade.marketdata.api.MarketSourceAdapter.RoundSummary;
import com.memetrade.marketdata.api.MarketSourceAdapter.TokenInfo;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceStatus.State;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.api.WatchedTokens;
import com.memetrade.marketdata.domain.MarketStore.CollectionWrite;
import com.memetrade.marketdata.domain.MarketStore.ObservedValue;
import com.memetrade.marketdata.domain.ObservationValidator.Validated;
import com.memetrade.marketdata.domain.SourceFetcher.Failed;
import com.memetrade.marketdata.domain.SourceFetcher.FetchOutcome;
import com.memetrade.marketdata.domain.SourceFetcher.Fetched;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * F1, one collection of one source (US2.3, BR1.5, BR1.11, NFR4.3).
 *
 * <ol>
 *   <li>the call (time limit and new attempts) happens OUTSIDE the transaction, so no connection is held while a
 *       source is slow;
 *   <li>a failure after the attempts: FAILED with the category; the current observations stay as they are;
 *   <li>a success: validation at the port (invalid tokens discarded and counted), then in ONE transaction the tokens,
 *       the current value of every field (a field not sent becomes {@code Unknown(NOT_PROVIDED)}, never zero), one
 *       sample per 5-minute bucket, the state (OK, or DEGRADED after a new attempt or a discard) and, when the state
 *       changed, the global mark. An interrupted collection leaves nothing half written.
 * </ol>
 */
public class MarketCollector {

    /** U2 (BR1.9): rounds in a row without any price after which a tolerant source is FAILED. */
    static final int TOLERATED_ROUNDS_WITHOUT_PRICE = 1;

    private final MarketStore store;
    private final SourceFetcher fetcher;
    private final UnitOfWork transactions;
    private final Clock clock;
    private final WatchedTokens watched;
    /** Rounds in a row without any price, per source; in memory, so a restart starts again at DEGRADED (accepted). */
    private final Map<String, Integer> roundsWithoutPrice = new ConcurrentHashMap<>();

    public MarketCollector(MarketStore store, SourceFetcher fetcher, UnitOfWork transactions, Clock clock) {
        this(store, fetcher, transactions, clock, WatchedTokens.NONE);
    }

    /** @param watched tokens other modules need priced (BR1.1), passed to every fetch */
    public MarketCollector(
            MarketStore store, SourceFetcher fetcher, UnitOfWork transactions, Clock clock, WatchedTokens watched) {
        this.store = store;
        this.fetcher = fetcher;
        this.transactions = transactions;
        this.clock = clock;
        this.watched = watched;
    }

    public CollectionOutcome collect(MarketSourceAdapter adapter) {
        Instant now = clock.instant();
        FetchRequest request =
                adapter.tolerantFailure() ? new FetchRequest(now, watched.watched()) : new FetchRequest(now);
        FetchOutcome fetched = fetcher.fetch(adapter, request);
        return switch (fetched) {
            case Failed failed -> fail(adapter, failed, now);
            case Fetched ok -> succeed(adapter, ok, now);
        };
    }

    /**
     * The call failed after its attempts. A simulated source is FAILED at once (unchanged); a tolerant source (U2) is
     * DEGRADED on the first round without a price and FAILED on the second one in a row (BR1.9).
     */
    private CollectionOutcome fail(MarketSourceAdapter adapter, Failed failed, Instant now) {
        String sourceId = adapter.source().sourceId();
        State status = adapter.tolerantFailure() ? withoutPrice(sourceId) : State.FAILED;
        return transactions.inTransaction(() -> {
            State previous = previousState(sourceId);
            store.updateSourceStatus(sourceId, status, Optional.of(failed.category()), now, Optional.empty());
            markIfChanged(previous, status);
            return new CollectionOutcome(
                    sourceId,
                    previous,
                    status,
                    Optional.of(failed.category()),
                    failed.retries(),
                    Map.of(),
                    0,
                    Optional.of(failed.exceptionClass()));
        });
    }

    /** One more round without price: DEGRADED while tolerated, then FAILED. */
    private State withoutPrice(String sourceId) {
        int rounds = roundsWithoutPrice.merge(sourceId, 1, Integer::sum);
        return rounds > TOLERATED_ROUNDS_WITHOUT_PRICE ? State.FAILED : State.DEGRADED;
    }

    private CollectionOutcome succeed(MarketSourceAdapter adapter, Fetched fetched, Instant now) {
        String sourceId = adapter.source().sourceId();
        Validated validated = ObservationValidator.validate(fetched.result(), adapter.source());
        Optional<RoundSummary> round = fetched.result().round();
        State status;
        Optional<FailureCategory> category;
        Optional<Instant> updatedAt = Optional.of(now);
        if (round.isPresent() && round.get().noPrice()) {
            // BR1.9: a round without any price is a failure of the source, tolerated once for a tolerant source.
            status = adapter.tolerantFailure() ? withoutPrice(sourceId) : State.FAILED;
            category = Optional.of(round.get().cut().orElse(FailureCategory.ERROR_RESPONSE));
            updatedAt = Optional.empty();
        } else {
            roundsWithoutPrice.remove(sourceId);
            boolean partial = round.isPresent() && !round.get().complete();
            boolean degraded = partial || fetched.retries() > 0 || validated.discardedTotal() > 0;
            status = degraded ? State.DEGRADED : State.OK;
            // A cut round names its cause (TIMEOUT or ERROR_RESPONSE); a round where the provider simply had no price
            // for some tokens, or a discarded token, is INVALID_PAYLOAD (the existing categories, no CHECK change).
            category = !degraded
                    ? Optional.empty()
                    : round.flatMap(RoundSummary::cut)
                            .or(fetched::retryCause)
                            .or(() -> Optional.of(FailureCategory.INVALID_PAYLOAD));
        }
        CollectionWrite write = new CollectionWrite(
                sourceId, now, validated.tokens(), values(adapter, validated, now, round.isPresent()));
        Optional<Instant> lastUpdated = updatedAt;
        return transactions.inTransaction(() -> {
            State previous = previousState(sourceId);
            store.writeCollection(write);
            store.updateSourceStatus(sourceId, status, category, now, lastUpdated);
            markIfChanged(previous, status);
            return new CollectionOutcome(
                    sourceId,
                    previous,
                    status,
                    category,
                    fetched.retries(),
                    validated.discarded(),
                    validated.tokens().size(),
                    Optional.empty());
        });
    }

    private State previousState(String sourceId) {
        return store.sourceState(sourceId)
                .map(SourceState::status)
                .orElseThrow(() -> new IllegalStateException("Source " + sourceId + " is not registered"));
    }

    private void markIfChanged(State previous, State current) {
        if (previous != current) {
            store.incrementStatusMark();
        }
    }

    /**
     * The values to write. A source with a round summary (U2) sends only the fields it renewed: only those are written
     * and the others keep their earlier observation with its own time (BR1.9). Any other source writes every field, a
     * missing one as {@code Unknown(NOT_PROVIDED)}, never zero.
     */
    private static Map<TokenId, Map<MarketField, ObservedValue>> values(
            MarketSourceAdapter adapter, Validated validated, Instant now, boolean onlySent) {
        Map<TokenId, Map<MarketField, ObservedValue>> values = new LinkedHashMap<>();
        for (TokenInfo token : validated.tokens()) {
            Map<MarketField, MarketValue> sent = validated.values().getOrDefault(token.token(), Map.of());
            Map<MarketField, ObservedValue> fields = new EnumMap<>(MarketField.class);
            for (MarketField field : MarketField.values()) {
                if (onlySent && !sent.containsKey(field)) {
                    continue;
                }
                MarketValue value = sent.getOrDefault(field, MarketValue.Unknown.notProvided(adapter.source()));
                Instant observedAt = value instanceof MarketValue.Known known ? known.observedAt() : now;
                fields.put(field, new ObservedValue(value, observedAt));
            }
            values.put(token.token(), fields);
        }
        return values;
    }

    /** CREATED_AT carries the creation time in epoch seconds; UNKNOWN keeps the age unknown (never zero). */
    public static Optional<Instant> creationTime(MarketValue value) {
        if (value instanceof MarketValue.Known known) {
            BigDecimal seconds = known.value();
            return Optional.of(Instant.ofEpochSecond(seconds.longValueExact()));
        }
        return Optional.empty();
    }
}
