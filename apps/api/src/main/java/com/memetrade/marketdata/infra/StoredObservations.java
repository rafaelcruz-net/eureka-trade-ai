package com.memetrade.marketdata.infra;

import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.ResolvedField;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.SourceStatus;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.api.UnknownReason;
import com.memetrade.marketdata.domain.FieldResolver;
import com.memetrade.marketdata.domain.FieldResolver.SourcedObservation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reads the current observations of a set of tokens, from ENABLED sources, with the state of each source, and resolves
 * every field with {@link FieldResolver} (F2). Shared by the C11 queries, the risk inputs and the list page.
 */
final class StoredObservations {

    static final String COLUMNS = """
            select o.chain, o.mint, o.field, o.status, o.value, o.unknown_reason, o.observed_at,
                   s.source_id, s.name, s.simulated, s.status as source_status
            from market_observation o join data_source s on s.source_id = o.source_id and s.enabled
            """;

    private StoredObservations() {}

    /** Observations of every token selected by {@code where} (a clause on {@code o}), grouped by token and field. */
    static Map<TokenId, Map<MarketField, List<SourcedObservation>>> read(JdbcClient.StatementSpec statement) {
        Map<TokenId, Map<MarketField, List<SourcedObservation>>> byToken = new LinkedHashMap<>();
        statement.query(rs -> {
            TokenId token = new TokenId(rs.getString("chain"), rs.getString("mint"));
            byToken.computeIfAbsent(token, t -> new EnumMap<>(MarketField.class))
                    .computeIfAbsent(MarketField.valueOf(rs.getString("field")), f -> new ArrayList<>())
                    .add(row(rs));
        });
        return byToken;
    }

    static Map<MarketField, ResolvedField> resolve(
            FieldResolver resolver, Map<MarketField, List<SourcedObservation>> fields, Instant now) {
        Map<MarketField, ResolvedField> resolved = new EnumMap<>(MarketField.class);
        for (MarketField field : MarketField.values()) {
            resolved.put(field, resolver.resolve(field, fields.getOrDefault(field, List.of()), now));
        }
        return resolved;
    }

    /** True when the token has observations and every one comes from a FAILED source (BR3.8). */
    static boolean allSourcesFailed(Map<MarketField, List<SourcedObservation>> fields) {
        List<SourcedObservation> all =
                fields.values().stream().flatMap(List::stream).toList();
        return !all.isEmpty() && all.stream().allMatch(o -> o.sourceState() == SourceStatus.State.FAILED);
    }

    private static SourcedObservation row(ResultSet rs) throws SQLException {
        boolean known = "KNOWN".equals(rs.getString("status"));
        return new SourcedObservation(
                new SourceRef(rs.getString("source_id"), rs.getString("name"), rs.getBoolean("simulated")),
                SourceStatus.State.valueOf(rs.getString("source_status")),
                known ? rs.getBigDecimal("value") : null,
                known ? null : UnknownReason.valueOf(rs.getString("unknown_reason")),
                rs.getTimestamp("observed_at").toInstant());
    }
}
