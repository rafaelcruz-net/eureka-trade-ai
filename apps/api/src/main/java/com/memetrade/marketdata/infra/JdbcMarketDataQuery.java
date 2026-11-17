package com.memetrade.marketdata.infra;

import com.memetrade.marketdata.api.Attribution;
import com.memetrade.marketdata.api.MarketDataQuery;
import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketRiskInputs;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.PriceVolumeWindow;
import com.memetrade.marketdata.api.SourceKind;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.SourceStatus;
import com.memetrade.marketdata.api.SourceStatusMarks;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.api.UnknownReason;
import com.memetrade.marketdata.domain.FieldResolver;
import com.memetrade.marketdata.domain.FieldResolver.SourcedObservation;
import com.memetrade.marketdata.domain.Freshness;
import com.memetrade.marketdata.domain.MarketStore;
import com.memetrade.marketdata.infra.coingecko.CoinGeckoProperties;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * C11 ({@link MarketDataQuery}), the batch inputs of the risk indicator ({@link MarketRiskInputs}) and C18
 * ({@link SourceStatusMarks}) over the MarketData tables. Fields are resolved by {@link FieldResolver} (F2) with the
 * injectable clock. A technical failure in C11 becomes {@code Unknown(SOURCE_UNAVAILABLE)}, logged with the exception
 * class only (NFR10.9).
 */
@Repository
public class JdbcMarketDataQuery implements MarketDataQuery, MarketRiskInputs, SourceStatusMarks {

    private static final Logger log = LoggerFactory.getLogger(JdbcMarketDataQuery.class);

    /** BR1.8: only tokens observed in the last 24 hours are listed and assessed. */
    public static final Duration HISTORY = Duration.ofHours(24);

    /** BR1.10: the previous price is a sample observed between 65 and 60 minutes ago, both ends included. */
    public static final Duration WINDOW_START = Duration.ofMinutes(65);

    public static final Duration WINDOW_END = Duration.ofMinutes(60);

    private static final String RECENT = " where exists (select 1 from market_observation r"
            + " where r.chain = o.chain and r.mint = o.mint and r.observed_at >= :since)";
    private static final String ONE_TOKEN = " where o.chain = :chain and o.mint = :mint";

    private final JdbcClient jdbc;
    private final FieldResolver resolver;
    private final MarketStore store;
    private final Clock clock;
    private final MarketDataProperties sources;
    private final CoinGeckoProperties coingecko;

    /** Without the configuration: the kind of the active source is derived from the stored sources. */
    public JdbcMarketDataQuery(JdbcClient jdbc, FieldResolver resolver, MarketStore store, Clock clock) {
        this(jdbc, resolver, store, clock, null, null);
    }

    /**
     * @param sources {@code MARKET_SOURCES}, the answer of {@link #activeSourceKind()} (U2 BR1.5)
     * @param coingecko the attribution of the real source (U2 BR1.8)
     */
    @org.springframework.beans.factory.annotation.Autowired
    public JdbcMarketDataQuery(
            JdbcClient jdbc,
            FieldResolver resolver,
            MarketStore store,
            Clock clock,
            MarketDataProperties sources,
            CoinGeckoProperties coingecko) {
        this.jdbc = jdbc;
        this.resolver = resolver;
        this.store = store;
        this.clock = clock;
        this.sources = sources;
        this.coingecko = coingecko;
    }

    /** U2 (BR1.5): SIMULATED as soon as {@code MARKET_SOURCES} names a simulated source, REAL otherwise. */
    @Override
    public SourceKind activeSourceKind() {
        if (sources == null) {
            return MarketDataQuery.super.activeSourceKind();
        }
        return sources.anySimulated() ? SourceKind.SIMULATED : SourceKind.REAL;
    }

    /** U2 (BR1.5, BR1.8): the text and link of the real source when {@code MARKET_SOURCES} names coingecko. */
    @Override
    public Optional<Attribution> attribution() {
        if (sources == null || coingecko == null || !sources.coingeckoConfigured()) {
            return Optional.empty();
        }
        return Optional.of(new Attribution(coingecko.attributionText(), coingecko.attributionUrl()));
    }

    @Override
    public MarketValue referencePrice(TokenId token) {
        return field(token, MarketField.PRICE);
    }

    @Override
    public MarketValue liquidity(TokenId token) {
        return field(token, MarketField.LIQUIDITY);
    }

    /**
     * U7 (BR1.10): {@code token.decimals} with the source of the most recent observation of the token and the instant
     * the token was first seen; a token without a row is {@code Unknown(NOT_YET_OBSERVED)}.
     */
    @Override
    public MarketValue decimals(TokenId token) {
        try {
            return jdbc.sql("""
                            select t.decimals, t.first_seen_at, s.source_id, s.name, s.simulated
                            from token t
                            join market_observation o on o.chain = t.chain and o.mint = t.mint
                            join data_source s on s.source_id = o.source_id
                            where t.chain = :chain and t.mint = :mint
                            order by o.observed_at desc, s.source_id
                            limit 1
                            """)
                    .param("chain", token.chain())
                    .param("mint", token.mint())
                    .query((rs, row) -> (MarketValue) new MarketValue.Known(
                            java.math.BigDecimal.valueOf(rs.getInt("decimals")),
                            new SourceRef(rs.getString("source_id"), rs.getString("name"), rs.getBoolean("simulated")),
                            rs.getTimestamp("first_seen_at").toInstant(),
                            false))
                    .optional()
                    .orElseGet(JdbcMarketDataQuery::notYetObserved);
        } catch (DataAccessException failure) {
            logFailure("decimals", failure);
            return new MarketValue.Unknown(UnknownReason.SOURCE_UNAVAILABLE, Optional.empty());
        }
    }

    @Override
    public MarketValue volume24h(TokenId token) {
        return field(token, MarketField.VOLUME_24H);
    }

    /**
     * 261113-trading-real-bot U3 (M4, Q3 of the NFR Design): the PRICE of every token of the set in ONE query, resolved
     * exactly like {@link #referencePrice(TokenId)}; a technical failure makes every price
     * {@code Unknown(SOURCE_UNAVAILABLE)}.
     */
    @Override
    public Map<TokenId, MarketValue> referencePrices(Set<TokenId> tokens) {
        Map<TokenId, MarketValue> prices = new LinkedHashMap<>();
        if (tokens.isEmpty()) {
            return prices;
        }
        Instant now = clock.instant();
        try {
            Map<TokenId, Map<MarketField, List<SourcedObservation>>> observations = StoredObservations.read(
                    keys(jdbc.sql(StoredObservations.COLUMNS + BATCH + " where o.field = 'PRICE'"), tokens));
            for (TokenId token : tokens) {
                List<SourcedObservation> price =
                        observations.getOrDefault(token, Map.of()).getOrDefault(MarketField.PRICE, List.of());
                prices.put(
                        token, resolver.resolve(MarketField.PRICE, price, now).value());
            }
        } catch (DataAccessException failure) {
            logFailure("referencePrices", failure);
            tokens.forEach(token ->
                    prices.put(token, new MarketValue.Unknown(UnknownReason.SOURCE_UNAVAILABLE, Optional.empty())));
        }
        return prices;
    }

    /** 261113-trading-real-bot U3 (BR5.2): {@link #decimals(TokenId)} of every token of the set in ONE query. */
    @Override
    public Map<TokenId, MarketValue> decimalsOf(Set<TokenId> tokens) {
        Map<TokenId, MarketValue> decimals = new LinkedHashMap<>();
        if (tokens.isEmpty()) {
            return decimals;
        }
        try {
            Map<TokenId, MarketValue> found = new HashMap<>();
            keys(jdbc.sql("""
                                    select distinct on (t.chain, t.mint) t.chain, t.mint, t.decimals, t.first_seen_at,
                                           s.source_id, s.name, s.simulated
                                    from token t
                                    join unnest(string_to_array(:chains, ','), string_to_array(:mints, ','))
                                      as k(chain, mint) on k.chain = t.chain and k.mint = t.mint
                                    join market_observation o on o.chain = t.chain and o.mint = t.mint
                                    join data_source s on s.source_id = o.source_id
                                    order by t.chain, t.mint, o.observed_at desc, s.source_id
                                    """), tokens).query(rs -> {
                found.put(
                        new TokenId(rs.getString("chain"), rs.getString("mint")),
                        new MarketValue.Known(
                                java.math.BigDecimal.valueOf(rs.getInt("decimals")),
                                new SourceRef(
                                        rs.getString("source_id"), rs.getString("name"), rs.getBoolean("simulated")),
                                rs.getTimestamp("first_seen_at").toInstant(),
                                false));
            });
            tokens.forEach(token -> decimals.put(token, found.getOrDefault(token, notYetObserved())));
        } catch (DataAccessException failure) {
            logFailure("decimalsOf", failure);
            tokens.forEach(token ->
                    decimals.put(token, new MarketValue.Unknown(UnknownReason.SOURCE_UNAVAILABLE, Optional.empty())));
        }
        return decimals;
    }

    @Override
    public Map<TokenId, String> symbolsOf(Set<TokenId> tokens) {
        Map<TokenId, String> symbols = new LinkedHashMap<>();
        if (tokens.isEmpty()) {
            return symbols;
        }
        try {
            keys(jdbc.sql("""
                                    select t.chain, t.mint, t.symbol from token t
                                    join unnest(string_to_array(:chains, ','), string_to_array(:mints, ','))
                                      as k(chain, mint) on k.chain = t.chain and k.mint = t.mint
                                    """), tokens).query(rs -> {
                symbols.put(new TokenId(rs.getString("chain"), rs.getString("mint")), rs.getString("symbol"));
            });
        } catch (DataAccessException failure) {
            logFailure("symbolsOf", failure);
        }
        return symbols;
    }

    /** Joins the observations with the set of tokens; chains and mints never contain a comma (Base58, ids). */
    private static final String BATCH = " join unnest(string_to_array(:chains, ','), string_to_array(:mints, ','))"
            + " as k(chain, mint) on k.chain = o.chain and k.mint = o.mint";

    private static JdbcClient.StatementSpec keys(JdbcClient.StatementSpec statement, Set<TokenId> tokens) {
        List<TokenId> ordered = List.copyOf(tokens);
        return statement
                .param(
                        "chains",
                        String.join(",", ordered.stream().map(TokenId::chain).toList()))
                .param(
                        "mints",
                        String.join(",", ordered.stream().map(TokenId::mint).toList()));
    }

    @Override
    @Transactional(readOnly = true)
    public PriceVolumeWindow window(TokenId token, Duration length) {
        Instant now = clock.instant();
        Duration bounded = length.compareTo(HISTORY) > 0 ? HISTORY : length;
        Instant from = JdbcMarketStore.bucketStart(now.minus(bounded));
        Instant to = JdbcMarketStore.bucketStart(now);
        try {
            Map<String, MarketValue> samples = samples(token, from, now);
            List<PriceVolumeWindow.Point> points = new ArrayList<>();
            for (Instant bucket = from; !bucket.isAfter(to); bucket = bucket.plus(JdbcMarketStore.BUCKET)) {
                points.add(new PriceVolumeWindow.Point(
                        bucket,
                        samples.getOrDefault("PRICE@" + bucket, notYetObserved()),
                        samples.getOrDefault("VOLUME_24H@" + bucket, notYetObserved())));
            }
            return new PriceVolumeWindow(token, from, to, points);
        } catch (DataAccessException failure) {
            logFailure("window", failure);
            return new PriceVolumeWindow(token, from, to, List.of());
        }
    }

    @Override
    public List<SourceStatus> sourceStatuses() {
        try {
            return store.sourceStatuses();
        } catch (DataAccessException failure) {
            logFailure("sourceStatuses", failure);
            return List.of();
        }
    }

    @Override
    public long globalMark() {
        return store.statusMark();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TokenInputs> recentTokens() {
        Instant now = clock.instant();
        Map<TokenId, Map<MarketField, List<SourcedObservation>>> observations = StoredObservations.read(
                jdbc.sql(StoredObservations.COLUMNS + RECENT).param("since", Timestamp.from(now.minus(HISTORY))));
        Map<TokenId, MarketValue> previous = previousPrices(Optional.empty(), now);
        List<TokenInputs> inputs = new ArrayList<>();
        observations.forEach((token, fields) -> inputs.add(inputs(token, fields, previous, now)));
        return inputs;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TokenInputs> inputsFor(TokenId token) {
        Instant now = clock.instant();
        if (!observedSince(token, now.minus(HISTORY))) {
            return Optional.empty();
        }
        Map<MarketField, List<SourcedObservation>> fields = observations(token).getOrDefault(token, Map.of());
        return Optional.of(inputs(token, fields, previousPrices(Optional.of(token), now), now));
    }

    /** True when the token has an observation of any source in the last 24 hours (BR1.8). */
    public boolean observedSince(TokenId token, Instant since) {
        return jdbc.sql("select exists (select 1 from market_observation where chain = :chain and mint = :mint"
                        + " and observed_at >= :since)")
                .param("chain", token.chain())
                .param("mint", token.mint())
                .param("since", Timestamp.from(since))
                .query(Boolean.class)
                .single();
    }

    private MarketValue field(TokenId token, MarketField field) {
        try {
            Map<MarketField, List<SourcedObservation>> fields =
                    observations(token).getOrDefault(token, Map.of());
            return resolver.resolve(field, fields.getOrDefault(field, List.of()), clock.instant())
                    .value();
        } catch (DataAccessException failure) {
            logFailure(field.name(), failure);
            return new MarketValue.Unknown(UnknownReason.SOURCE_UNAVAILABLE, Optional.empty());
        }
    }

    private Map<TokenId, Map<MarketField, List<SourcedObservation>>> observations(TokenId token) {
        return StoredObservations.read(jdbc.sql(StoredObservations.COLUMNS + ONE_TOKEN)
                .param("chain", token.chain())
                .param("mint", token.mint()));
    }

    private TokenInputs inputs(
            TokenId token,
            Map<MarketField, List<SourcedObservation>> fields,
            Map<TokenId, MarketValue> previous,
            Instant now) {
        return new TokenInputs(
                token,
                StoredObservations.allSourcesFailed(fields),
                StoredObservations.resolve(resolver, fields, now),
                previous.getOrDefault(token, notYetObserved()));
    }

    /** BR1.10: per token, the most recent KNOWN PRICE sample of a source not FAILED in [now - 65 min, now - 60 min]. */
    private Map<TokenId, MarketValue> previousPrices(Optional<TokenId> only, Instant now) {
        String filter = only.isPresent() ? " and x.chain = :chain and x.mint = :mint" : "";
        var statement = jdbc.sql("""
                        select distinct on (x.chain, x.mint) x.chain, x.mint, x.value, x.observed_at,
                               s.source_id, s.name, s.simulated
                        from observation_sample x
                        join data_source s on s.source_id = x.source_id and s.enabled and s.status <> 'FAILED'
                        where x.field = 'PRICE' and x.status = 'KNOWN'
                          and x.observed_at between :from and :to""" + filter + " order by x.chain, x.mint, x.observed_at desc, x.source_id")
                .param("from", Timestamp.from(now.minus(WINDOW_START)))
                .param("to", Timestamp.from(now.minus(WINDOW_END)));
        if (only.isPresent()) {
            statement = statement
                    .param("chain", only.get().chain())
                    .param("mint", only.get().mint());
        }
        Map<TokenId, MarketValue> previous = new HashMap<>();
        statement.query(rs -> {
            Instant observedAt = rs.getTimestamp("observed_at").toInstant();
            previous.put(
                    new TokenId(rs.getString("chain"), rs.getString("mint")),
                    new MarketValue.Known(
                            rs.getBigDecimal("value"),
                            new SourceRef(rs.getString("source_id"), rs.getString("name"), rs.getBoolean("simulated")),
                            observedAt,
                            Freshness.isStale(observedAt, now)));
        });
        return previous;
    }

    private Map<String, MarketValue> samples(TokenId token, Instant from, Instant now) {
        Map<String, MarketValue> samples = new HashMap<>();
        jdbc.sql("""
                        select distinct on (x.field, x.bucket_start) x.field, x.bucket_start, x.value, x.observed_at,
                               s.source_id, s.name, s.simulated
                        from observation_sample x
                        join data_source s on s.source_id = x.source_id and s.enabled and s.status <> 'FAILED'
                        where x.chain = :chain and x.mint = :mint and x.field in ('PRICE', 'VOLUME_24H')
                          and x.status = 'KNOWN' and x.bucket_start >= :from
                        order by x.field, x.bucket_start, x.observed_at desc, x.source_id
                        """)
                .param("chain", token.chain())
                .param("mint", token.mint())
                .param("from", Timestamp.from(from))
                .query(rs -> {
                    Instant observedAt = rs.getTimestamp("observed_at").toInstant();
                    samples.put(
                            rs.getString("field") + "@"
                                    + rs.getTimestamp("bucket_start").toInstant(),
                            new MarketValue.Known(
                                    rs.getBigDecimal("value"),
                                    new SourceRef(
                                            rs.getString("source_id"),
                                            rs.getString("name"),
                                            rs.getBoolean("simulated")),
                                    observedAt,
                                    Freshness.isStale(observedAt, now)));
                });
        return samples;
    }

    private static MarketValue notYetObserved() {
        return new MarketValue.Unknown(UnknownReason.NOT_YET_OBSERVED, Optional.empty());
    }

    private static void logFailure(String operation, DataAccessException failure) {
        log.error(
                "Market data query failed: operation={}, exceptionClass={}",
                operation,
                failure.getClass().getSimpleName());
    }
}
