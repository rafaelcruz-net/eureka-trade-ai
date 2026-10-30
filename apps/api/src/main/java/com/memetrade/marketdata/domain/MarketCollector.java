package com.memetrade.marketdata.domain;

import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketSourceAdapter;
import com.memetrade.marketdata.api.MarketSourceAdapter.FetchResult;
import com.memetrade.marketdata.api.MarketSourceAdapter.Observation;
import com.memetrade.marketdata.api.MarketSourceAdapter.TokenInfo;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.TokenId;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * One collection of a source (F5): writes the current observation of every field with source and time and marks the
 * source OK. A field missing from the result becomes UNKNOWN NOT_PROVIDED, never zero. A failing fetch marks the
 * source FAILED and keeps the previous observations with their times (they turn stale after 5 minutes).
 */
public class MarketCollector {

    private static final Logger log = LoggerFactory.getLogger(MarketCollector.class);

    private final MarketSourceAdapter adapter;
    private final MarketStore store;
    private final Clock clock;

    public MarketCollector(MarketSourceAdapter adapter, MarketStore store, Clock clock) {
        this.adapter = adapter;
        this.store = store;
        this.clock = clock;
    }

    public MarketSourceAdapter adapter() {
        return adapter;
    }

    /** Returns true when the collection succeeded. */
    @Transactional
    public boolean collect() {
        Instant now = clock.instant();
        FetchResult result;
        try {
            result = adapter.fetch(new MarketSourceAdapter.FetchRequest(now));
        } catch (RuntimeException failure) {
            store.sourceFailed(adapter.source(), now);
            log.warn(
                    "Market source collection failed: source={}, error={}",
                    adapter.source().sourceId(),
                    failure.getClass().getSimpleName());
            return false;
        }
        // The source row must exist before its observations (foreign key); both commit in this one transaction,
        // so a failure while persisting rolls the OK status back too.
        store.sourceCollected(adapter.source(), now);
        persist(result, now);
        log.info(
                "Market source collected: source={}, tokens={}",
                adapter.source().sourceId(),
                result.tokens().size());
        return true;
    }

    private void persist(FetchResult result, Instant now) {
        Map<TokenId, Map<MarketField, MarketValue>> byToken = new HashMap<>();
        for (Observation observation : result.observations()) {
            byToken.computeIfAbsent(observation.token(), t -> new EnumMap<>(MarketField.class))
                    .put(observation.field(), observation.value());
        }
        for (TokenInfo token : result.tokens()) {
            store.upsertToken(token, now);
            Map<MarketField, MarketValue> values = byToken.getOrDefault(token.token(), Map.of());
            for (MarketField field : MarketField.values()) {
                MarketValue value = values.getOrDefault(field, MarketValue.Unknown.notProvided(adapter.source()));
                store.upsertObservation(
                        token.token(), adapter.source().sourceId(), field, value, observedAt(value, now));
            }
            store.updateCreationTime(token.token(), creationTime(values.get(MarketField.CREATED_AT)));
        }
    }

    private static Instant observedAt(MarketValue value, Instant now) {
        return value instanceof MarketValue.Known known ? known.observedAt() : now;
    }

    private static Optional<Instant> creationTime(MarketValue value) {
        if (value instanceof MarketValue.Known known) {
            BigDecimal seconds = known.value();
            return Optional.of(Instant.ofEpochSecond(seconds.longValueExact()));
        }
        return Optional.empty();
    }
}
