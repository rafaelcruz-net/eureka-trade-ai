package com.memetrade.marketdata.domain;

import com.memetrade.marketdata.api.FailureCategory;
import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketSourceAdapter;
import com.memetrade.marketdata.api.MarketSourceAdapter.FetchRequest;
import com.memetrade.marketdata.api.MarketSourceAdapter.TokenInfo;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceStatus.State;
import com.memetrade.marketdata.api.TokenId;
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

    private final MarketStore store;
    private final SourceFetcher fetcher;
    private final UnitOfWork transactions;
    private final Clock clock;

    public MarketCollector(MarketStore store, SourceFetcher fetcher, UnitOfWork transactions, Clock clock) {
        this.store = store;
        this.fetcher = fetcher;
        this.transactions = transactions;
        this.clock = clock;
    }

    public CollectionOutcome collect(MarketSourceAdapter adapter) {
        Instant now = clock.instant();
        String sourceId = adapter.source().sourceId();
        FetchOutcome fetched = fetcher.fetch(adapter, new FetchRequest(now));
        return switch (fetched) {
            case Failed failed -> fail(sourceId, failed, now);
            case Fetched ok -> succeed(adapter, ok, now);
        };
    }

    private CollectionOutcome fail(String sourceId, Failed failed, Instant now) {
        return transactions.inTransaction(() -> {
            State previous = previousState(sourceId);
            store.updateSourceStatus(sourceId, State.FAILED, Optional.of(failed.category()), now, Optional.empty());
            markIfChanged(previous, State.FAILED);
            return new CollectionOutcome(
                    sourceId,
                    previous,
                    State.FAILED,
                    Optional.of(failed.category()),
                    failed.retries(),
                    Map.of(),
                    0,
                    Optional.of(failed.exceptionClass()));
        });
    }

    private CollectionOutcome succeed(MarketSourceAdapter adapter, Fetched fetched, Instant now) {
        String sourceId = adapter.source().sourceId();
        Validated validated = ObservationValidator.validate(fetched.result(), adapter.source());
        boolean degraded = fetched.retries() > 0 || validated.discardedTotal() > 0;
        State status = degraded ? State.DEGRADED : State.OK;
        Optional<FailureCategory> category = !degraded
                ? Optional.empty()
                : fetched.retryCause().or(() -> Optional.of(FailureCategory.INVALID_PAYLOAD));
        CollectionWrite write = new CollectionWrite(sourceId, now, validated.tokens(), values(adapter, validated, now));
        return transactions.inTransaction(() -> {
            State previous = previousState(sourceId);
            store.writeCollection(write);
            store.updateSourceStatus(sourceId, status, category, now, Optional.of(now));
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

    private static Map<TokenId, Map<MarketField, ObservedValue>> values(
            MarketSourceAdapter adapter, Validated validated, Instant now) {
        Map<TokenId, Map<MarketField, ObservedValue>> values = new LinkedHashMap<>();
        for (TokenInfo token : validated.tokens()) {
            Map<MarketField, MarketValue> sent = validated.values().getOrDefault(token.token(), Map.of());
            Map<MarketField, ObservedValue> fields = new EnumMap<>(MarketField.class);
            for (MarketField field : MarketField.values()) {
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
