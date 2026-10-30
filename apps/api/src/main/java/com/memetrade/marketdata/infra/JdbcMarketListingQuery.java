package com.memetrade.marketdata.infra;

import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketListingQuery;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.SourceStatus;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.domain.ObservationValues;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keyset-paged listing over the composite index {@code (sort_group, sort_key, mint, chain)}: never OFFSET, and the
 * observations of the whole page are read in ONE query (performance-design.md).
 */
@Repository
public class JdbcMarketListingQuery implements MarketListingQuery {

    private final JdbcClient jdbc;
    private final Clock clock;

    public JdbcMarketListingQuery(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public ListingPage page(Optional<ListingPosition> after, Instant cutoff, int limit) {
        List<Row> rows = tokens(after, cutoff, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<Row> page = hasMore ? rows.subList(0, limit) : rows;
        Map<TokenId, Map<MarketField, MarketValue>> values = observations(page);
        List<ListedToken> tokens = new ArrayList<>();
        for (Row row : page) {
            Map<MarketField, MarketValue> byField = new EnumMap<>(MarketField.class);
            Map<MarketField, MarketValue> stored = values.getOrDefault(row.token(), Map.of());
            for (MarketField field : MarketField.values()) {
                byField.put(field, stored.getOrDefault(field, ObservationValues.notYetObserved()));
            }
            tokens.add(new ListedToken(row.token(), row.symbol(), row.name(), row.position(), byField));
        }
        return new ListingPage(tokens, hasMore);
    }

    @Override
    @Transactional(readOnly = true)
    public long count(Instant cutoff) {
        return jdbc.sql("select count(*) from token where first_seen_at <= :cutoff")
                .param("cutoff", Timestamp.from(cutoff))
                .query(Long.class)
                .single();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SourceStatus> sources() {
        return jdbc.sql("select * from data_source order by source_id")
                .query((rs, n) -> {
                    Timestamp updated = rs.getTimestamp("last_updated_at");
                    return new SourceStatus(
                            new SourceRef(rs.getString("source_id"), rs.getString("name"), rs.getBoolean("simulated")),
                            SourceStatus.State.valueOf(rs.getString("status")),
                            Optional.ofNullable(updated).map(Timestamp::toInstant));
                })
                .list();
    }

    private List<Row> tokens(Optional<ListingPosition> after, Instant cutoff, int limit) {
        String keyset = after.isPresent() ? " and (sort_group, sort_key, mint, chain) > (:g, :k, :m, :c)" : "";
        var statement = jdbc.sql("select chain, mint, symbol, name, sort_group, sort_key from token"
                        + " where first_seen_at <= :cutoff" + keyset
                        + " order by sort_group, sort_key, mint, chain limit :limit")
                .param("cutoff", Timestamp.from(cutoff))
                .param("limit", limit);
        if (after.isPresent()) {
            statement = statement
                    .param("g", after.get().group())
                    .param("k", after.get().sortKey())
                    .param("m", after.get().mint())
                    .param("c", after.get().chain());
        }
        return statement
                .query((rs, n) -> new Row(
                        new TokenId(rs.getString("chain"), rs.getString("mint")),
                        rs.getString("symbol"),
                        rs.getString("name"),
                        new ListingPosition(
                                rs.getInt("sort_group"),
                                rs.getLong("sort_key"),
                                rs.getString("mint"),
                                rs.getString("chain"))))
                .list();
    }

    private Map<TokenId, Map<MarketField, MarketValue>> observations(List<Row> page) {
        if (page.isEmpty()) {
            return Map.of();
        }
        Set<TokenId> wanted = page.stream().map(Row::token).collect(Collectors.toSet());
        Instant now = clock.instant();
        Map<TokenId, Map<MarketField, MarketValue>> result = new HashMap<>();
        jdbc.sql("""
                        select o.chain, o.mint, o.field, o.status, o.value, o.unknown_reason, o.observed_at,
                               s.source_id, s.name, s.simulated
                        from market_observation o join data_source s on s.source_id = o.source_id
                        where o.mint in (:mints)
                        """)
                .param("mints", wanted.stream().map(TokenId::mint).toList())
                .query(rs -> {
                    TokenId token = new TokenId(rs.getString("chain"), rs.getString("mint"));
                    if (!wanted.contains(token)) {
                        return;
                    }
                    SourceRef source =
                            new SourceRef(rs.getString("source_id"), rs.getString("name"), rs.getBoolean("simulated"));
                    MarketValue value = ObservationValues.fromStored(
                            rs.getString("status"),
                            rs.getBigDecimal("value"),
                            rs.getString("unknown_reason"),
                            source,
                            rs.getTimestamp("observed_at").toInstant(),
                            now);
                    result.computeIfAbsent(token, t -> new EnumMap<>(MarketField.class))
                            .put(MarketField.valueOf(rs.getString("field")), value);
                });
        return result;
    }

    private record Row(TokenId token, String symbol, String name, ListingPosition position) {}
}
