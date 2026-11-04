package com.memetrade.marketdata.infra;

import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketListingQuery;
import com.memetrade.marketdata.api.SourceStatus;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.domain.FieldResolver;
import com.memetrade.marketdata.domain.FieldResolver.SourcedObservation;
import com.memetrade.marketdata.domain.MarketStore;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single list query of performance-design.md (F3), with CTEs:
 *
 * <ol>
 *   <li>tokens observed in the last 24 hours by a source that is enabled and not FAILED (BR1.3, BR1.8);
 *   <li>per token and field, the most recent KNOWN value of those sources ({@code DISTINCT ON}, BR1.2), pivoted;
 *   <li>the stored assessment ({@code risk_assessment}, one row per token); an unavailable or absent indicator is
 *       UNKNOWN for the risk filter and goes last in the risk order (BR2.3);
 *   <li>per active filter "fails with a known value" and "unknown"; the counters {@code total}, {@code excludedByUnknown}
 *       and {@code pendingNewTokens} by aggregation over the whole photo (BR2.2, BR2.6, NFR3.4);
 *   <li>the order of the chosen field with UNKNOWN last and ties broken by mint then chain in byte order (BR2.5), the
 *       page continued after the cursor position by tuple comparison, never OFFSET.
 * </ol>
 *
 * Every value of the request is a bind parameter; the sort column and direction come from closed enums only (NFR6.2).
 * The freshness and divergence of each field are resolved in Java for the page only, in a second query.
 *
 * <p>The query joins the {@code risk_assessment} table of the RiskAssessment module by the decision Q3 of the NFR
 * Design (one query, no read model); it is the only read of that table outside its module.
 */
@Repository
public class JdbcMarketListingQuery implements MarketListingQuery {

    private static final String SINCE = "since";

    private final JdbcClient jdbc;
    private final FieldResolver resolver;
    private final MarketStore store;
    private final Clock clock;

    public JdbcMarketListingQuery(JdbcClient jdbc, FieldResolver resolver, MarketStore store, Clock clock) {
        this.jdbc = jdbc;
        this.resolver = resolver;
        this.store = store;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public ListingResult list(ListingRequest request) {
        Instant now = clock.instant();
        Sql sql = build(request, now);
        List<Row> rows = new ArrayList<>();
        long[] counts = new long[3];
        sql.statement(jdbc).query(rs -> {
            counts[0] = rs.getLong("total");
            counts[1] = rs.getLong("excluded");
            counts[2] = rs.getLong("pending");
            if (rs.getString("mint") != null) {
                rows.add(row(rs));
            }
        });
        boolean hasMore = rows.size() > request.limit();
        List<Row> page = hasMore ? rows.subList(0, request.limit()) : rows;
        Map<TokenId, Map<MarketField, List<SourcedObservation>>> observations = observations(page);
        List<ListedToken> tokens = new ArrayList<>();
        for (Row row : page) {
            tokens.add(new ListedToken(
                    row.token(),
                    row.symbol(),
                    row.name(),
                    StoredObservations.resolve(resolver, observations.getOrDefault(row.token(), Map.of()), now),
                    row.risk(),
                    row.position()));
        }
        return new ListingResult(tokens, hasMore, counts[0], counts[1], counts[2]);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SourceStatus> sources() {
        return store.sourceStatuses();
    }

    /** The plan of the list query (NFR2.6 check of the tests and of the load script). */
    @Transactional(readOnly = true)
    public String explain(ListingRequest request) {
        Sql sql = build(request, clock.instant());
        StringBuilder plan = new StringBuilder();
        new Sql("explain " + sql.text(), sql.params()).statement(jdbc).query(rs -> {
            plan.append(rs.getString(1)).append('\n');
        });
        return plan.toString();
    }

    private record Sql(String text, Map<String, Object> params) {
        JdbcClient.StatementSpec statement(JdbcClient jdbc) {
            JdbcClient.StatementSpec statement = jdbc.sql(text);
            for (Map.Entry<String, Object> param : params.entrySet()) {
                statement = statement.param(param.getKey(), param.getValue());
            }
            return statement;
        }
    }

    static Sql build(ListingRequest request, Instant now) {
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put(SINCE, Timestamp.from(now.minus(JdbcMarketDataQuery.HISTORY)));
        params.put("snapshot", Timestamp.from(request.snapshotAt()));
        params.put("nowEpoch", BigDecimal.valueOf(now.getEpochSecond()));
        params.put("limit", request.limit() + 1);
        Filters filters = request.filters();
        List<String> fails = new ArrayList<>();
        List<String> unknowns = new ArrayList<>();
        filters.minLiquidity().ifPresent(v -> minimum("liquidity", "minLiquidity", v, params, fails, unknowns));
        filters.minVolume24h().ifPresent(v -> minimum("volume", "minVolume", v, params, fails, unknowns));
        filters.minHolders().ifPresent(v -> minimum("holders", "minHolders", v, params, fails, unknowns));
        filters.maxAgeMinutes().ifPresent(minutes -> {
            params.put("maxAgeSeconds", BigDecimal.valueOf(minutes).multiply(BigDecimal.valueOf(60)));
            fails.add("coalesce((:nowEpoch - created) > :maxAgeSeconds, false)");
            unknowns.add("created is null");
        });
        filters.maxRiskScore().ifPresent(max -> {
            params.put("maxRisk", max);
            fails.add("coalesce(risk_score > :maxRisk, false)");
            unknowns.add("risk_score is null");
        });
        filters.sourceId().ifPresent(id -> {
            params.put("sourceId", id);
            fails.add("not has_source");
        });
        String hasSource = filters.sourceId().isPresent()
                ? "exists (select 1 from market_observation h join live l2 on l2.source_id = h.source_id"
                        + " where h.chain = c.chain and h.mint = c.mint and h.source_id = :sourceId"
                        + " and h.observed_at >= :since)"
                : "true";
        boolean descending = effectiveDescending(request.sort(), request.order());
        String keyset = keyset(request.after(), descending, params);
        String text = """
                with live as (
                  select source_id from data_source where enabled and status <> 'FAILED'
                ),
                cand as (
                  select t.chain, t.mint, t.symbol, t.name, t.first_seen_at
                  from token t
                  where exists (select 1 from market_observation r join live l on l.source_id = r.source_id
                                where r.chain = t.chain and r.mint = t.mint and r.observed_at >= :since%s)
                ),
                pivot as (
                  select c.chain, c.mint, c.symbol, c.name, c.first_seen_at,
                         %s as liquidity, %s as volume, %s as holders, %s as created,
                         %s as has_source
                  from cand c
                ),
                scored as (
                  select v.*, ra.status as risk_status, ra.score as stored_score, ra.band as risk_band,
                         ra.unavailable_reason as risk_reason, ra.weight_set_version as risk_version,
                         case when ra.status = 'AVAILABLE' then ra.score end as risk_score
                  from pivot v left join risk_assessment ra on ra.chain = v.chain and ra.mint = v.mint
                ),
                classified as (
                  select s.*, (%s) as fails, (%s) as unknowns, (s.first_seen_at <= :snapshot) as in_snapshot,
                         %s as sort_value
                  from scored s
                ),
                ordered as (
                  select x.*, case when x.sort_value is null then 1 else 0 end as grp from classified x
                ),
                counted as (
                  select count(*) filter (where in_snapshot and not fails and not unknowns) as total,
                         count(*) filter (where in_snapshot and not fails and unknowns) as excluded,
                         count(*) filter (where not in_snapshot and not fails and not unknowns) as pending
                  from ordered
                )
                select k.total, k.excluded, k.pending, p.* from counted k
                left join lateral (
                  select * from ordered
                  where in_snapshot and not fails and not unknowns %s
                  order by grp, sort_value %s, mint collate "C", chain collate "C"
                  limit :limit
                ) p on true
                """.formatted(
                        // A source filter narrows the candidates at once: a token of another source fails it anyway.
                        filters.sourceId().isPresent() ? " and r.source_id = :sourceId" : "",
                        pick("LIQUIDITY"),
                        pick("VOLUME_24H"),
                        pick("HOLDERS"),
                        pick("CREATED_AT"),
                        hasSource,
                        fails.isEmpty() ? "false" : String.join(" or ", fails),
                        unknowns.isEmpty() ? "false" : String.join(" or ", unknowns),
                        sortExpression(request.sort()),
                        keyset,
                        descending ? "desc" : "asc");
        return new Sql(text, params);
    }

    /**
     * Stage 2 for one field: the most recent KNOWN value among the live sources, ties to the smallest source id (BR1.2),
     * read through the index {@code (chain, mint, field, observed_at DESC, source_id)} per token. A lookup per token
     * keeps the plan the same whatever the table statistics say (a bulk collection can leave them behind).
     */
    private static String pick(String field) {
        return "(select o.value from market_observation o where o.chain = c.chain and o.mint = c.mint"
                + " and o.field = '" + field + "' and o.status = 'KNOWN'"
                + " and o.source_id in (select source_id from live)"
                + " order by o.observed_at desc, o.source_id limit 1)";
    }

    /** AGE ascending lists the youngest first: the creation time descending (stable between pages, BR2.6). */
    static boolean effectiveDescending(SortField sort, SortOrder order) {
        boolean descending = order == SortOrder.DESC;
        return sort == SortField.AGE ? !descending : descending;
    }

    private static String sortExpression(SortField sort) {
        return switch (sort) {
            case AGE -> "s.created";
            case LIQUIDITY -> "s.liquidity";
            case VOLUME_24H -> "s.volume";
            case HOLDERS -> "s.holders";
            case RISK_SCORE -> "s.risk_score::numeric";
        };
    }

    private static String keyset(Optional<Position> after, boolean descending, Map<String, Object> params) {
        if (after.isEmpty()) {
            return "";
        }
        Position position = after.get();
        params.put("afterMint", position.mint());
        params.put("afterChain", position.chain());
        String tie = "(mint collate \"C\", chain collate \"C\") > (:afterMint, :afterChain)";
        if (position.sortValue().isEmpty()) {
            return " and grp = 1 and " + tie;
        }
        params.put("afterValue", position.sortValue().get());
        String beyond = descending ? "sort_value < :afterValue" : "sort_value > :afterValue";
        return " and ((grp = 0 and (" + beyond + " or (sort_value = :afterValue and " + tie + "))) or grp = 1)";
    }

    private static void minimum(
            String column,
            String param,
            BigDecimal value,
            Map<String, Object> params,
            List<String> fails,
            List<String> unknowns) {
        params.put(param, value);
        fails.add("coalesce(" + column + " < :" + param + ", false)");
        unknowns.add(column + " is null");
    }

    private Map<TokenId, Map<MarketField, List<SourcedObservation>>> observations(List<Row> page) {
        if (page.isEmpty()) {
            return Map.of();
        }
        List<String> chains =
                page.stream().map(r -> r.token().chain()).distinct().toList();
        List<String> mints = page.stream().map(r -> r.token().mint()).toList();
        Map<TokenId, Map<MarketField, List<SourcedObservation>>> all = StoredObservations.read(
                jdbc.sql(StoredObservations.COLUMNS + " where o.chain in (:chains) and o.mint in (:mints)")
                        .param("chains", chains)
                        .param("mints", mints));
        all.keySet().retainAll(page.stream().map(Row::token).toList());
        return all;
    }

    private static Row row(ResultSet rs) throws SQLException {
        TokenId token = new TokenId(rs.getString("chain"), rs.getString("mint"));
        int storedScore = rs.getInt("stored_score");
        boolean hasScore = !rs.wasNull();
        int version = rs.getInt("risk_version");
        boolean hasVersion = !rs.wasNull();
        ListedRisk risk = new ListedRisk(
                Optional.ofNullable(rs.getString("risk_status")),
                hasScore ? Optional.of(storedScore) : Optional.empty(),
                Optional.ofNullable(rs.getString("risk_band")),
                Optional.ofNullable(rs.getString("risk_reason")),
                hasVersion ? Optional.of(version) : Optional.empty());
        return new Row(
                token,
                rs.getString("symbol"),
                rs.getString("name"),
                risk,
                new Position(Optional.ofNullable(rs.getBigDecimal("sort_value")), token.mint(), token.chain()));
    }

    private record Row(TokenId token, String symbol, String name, ListedRisk risk, Position position) {}
}
