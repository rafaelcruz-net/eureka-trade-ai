package com.memetrade.marketdata.infra;

import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketSourceAdapter.TokenInfo;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.SourceStatus;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.domain.MarketStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** PostgreSQL writes of the MarketData module (data_source, token, market_observation). */
@Repository
public class JdbcMarketStore implements MarketStore {

    private final JdbcClient jdbc;

    public JdbcMarketStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void sourceCollected(SourceRef source, Instant collectedAt) {
        jdbc.sql("""
                        insert into data_source (source_id, name, simulated, status, last_updated_at, last_attempt_at)
                        values (:id, :name, :simulated, 'OK', :at, :at)
                        on conflict (source_id) do update
                          set name = excluded.name, simulated = excluded.simulated, status = 'OK',
                              last_updated_at = excluded.last_updated_at, last_attempt_at = excluded.last_attempt_at
                        """)
                .param("id", source.sourceId())
                .param("name", source.name())
                .param("simulated", source.simulated())
                .param("at", Timestamp.from(collectedAt))
                .update();
    }

    @Override
    public void sourceFailed(SourceRef source, Instant attemptedAt) {
        jdbc.sql("""
                        insert into data_source (source_id, name, simulated, status, last_updated_at, last_attempt_at)
                        values (:id, :name, :simulated, 'FAILED', null, :at)
                        on conflict (source_id) do update set status = 'FAILED', last_attempt_at = excluded.last_attempt_at
                        """)
                .param("id", source.sourceId())
                .param("name", source.name())
                .param("simulated", source.simulated())
                .param("at", Timestamp.from(attemptedAt))
                .update();
    }

    @Override
    public void upsertToken(TokenInfo token, Instant seenAt) {
        jdbc.sql("""
                        insert into token (chain, mint, symbol, name, decimals, first_seen_at)
                        values (:chain, :mint, :symbol, :name, :decimals, :seen)
                        on conflict (chain, mint) do update
                          set symbol = excluded.symbol, name = excluded.name, decimals = excluded.decimals
                        """)
                .param("chain", token.token().chain())
                .param("mint", token.token().mint())
                .param("symbol", token.symbol())
                .param("name", token.name())
                .param("decimals", token.decimals())
                .param("seen", Timestamp.from(seenAt))
                .update();
    }

    @Override
    public void upsertObservation(
            TokenId token, String sourceId, MarketField field, MarketValue value, Instant observedAt) {
        boolean known = value instanceof MarketValue.Known;
        jdbc.sql("""
                        insert into market_observation
                          (observation_id, chain, mint, source_id, field, status, value, unknown_reason, observed_at)
                        values (:id, :chain, :mint, :source, :field, :status, :value, :reason, :observedAt)
                        on conflict (chain, mint, source_id, field) do update
                          set status = excluded.status, value = excluded.value,
                              unknown_reason = excluded.unknown_reason, observed_at = excluded.observed_at
                        """)
                .param("id", UUID.randomUUID())
                .param("chain", token.chain())
                .param("mint", token.mint())
                .param("source", sourceId)
                .param("field", field.name())
                .param("status", known ? "KNOWN" : "UNKNOWN")
                .param("value", value instanceof MarketValue.Known k ? k.value() : null)
                .param(
                        "reason",
                        value instanceof MarketValue.Unknown u ? u.reason().name() : null)
                .param("observedAt", Timestamp.from(observedAt))
                .update();
    }

    @Override
    public void updateCreationTime(TokenId token, Optional<Instant> createdAt) {
        jdbc.sql("""
                        update token set token_created_at = :created, sort_group = :group, sort_key = :key
                        where chain = :chain and mint = :mint
                        """)
                .param("created", createdAt.map(Timestamp::from).orElse(null))
                .param("group", createdAt.isPresent() ? 0 : 1)
                .param("key", createdAt.map(JdbcMarketStore::sortKey).orElse(0L))
                .param("chain", token.chain())
                .param("mint", token.mint())
                .update();
    }

    @Override
    public Optional<SourceStatus> sourceStatus(String sourceId) {
        return jdbc.sql("select * from data_source where source_id = :id")
                .param("id", sourceId)
                .query((rs, n) -> {
                    Timestamp updated = rs.getTimestamp("last_updated_at");
                    return new SourceStatus(
                            new SourceRef(rs.getString("source_id"), rs.getString("name"), rs.getBoolean("simulated")),
                            SourceStatus.State.valueOf(rs.getString("status")),
                            Optional.ofNullable(updated).map(Timestamp::toInstant));
                })
                .optional();
    }

    /** Newest first in ascending order: the negated creation time in epoch microseconds. */
    static long sortKey(Instant createdAt) {
        return -ChronoUnit.MICROS.between(Instant.EPOCH, createdAt);
    }
}
