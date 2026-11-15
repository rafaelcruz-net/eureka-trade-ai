package com.memetrade.marketdata.infra;

import com.memetrade.marketdata.api.FailureCategory;
import com.memetrade.marketdata.api.MarketField;
import com.memetrade.marketdata.api.MarketSourceAdapter.TokenInfo;
import com.memetrade.marketdata.api.MarketValue;
import com.memetrade.marketdata.api.SourceRef;
import com.memetrade.marketdata.api.SourceStatus;
import com.memetrade.marketdata.api.TokenId;
import com.memetrade.marketdata.domain.MarketCollector;
import com.memetrade.marketdata.domain.MarketStore;
import com.memetrade.marketdata.domain.SourceState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL writes of the MarketData module ({@code data_source}, {@code token}, {@code market_observation},
 * {@code observation_sample}, {@code source_status_mark}). A collection is written in batches (performance-design.md):
 * {@code ON CONFLICT DO UPDATE} for the current values and {@code ON CONFLICT DO NOTHING} for the samples.
 */
@Repository
public class JdbcMarketStore implements MarketStore {

    /** Resolution of the history (NFR2.4): one sample per token, source and field per 5-minute UTC bucket. */
    public static final Duration BUCKET = Duration.ofMinutes(5);

    private static final String UPSERT_TOKEN = """
            insert into token (chain, mint, symbol, name, decimals, first_seen_at)
            values (:chain, :mint, :symbol, :name, :decimals, :seen)
            on conflict (chain, mint) do update
              set symbol = excluded.symbol, name = excluded.name, decimals = excluded.decimals
            """;
    private static final String UPSERT_OBSERVATION = """
            insert into market_observation
              (observation_id, chain, mint, source_id, field, status, value, unknown_reason, observed_at)
            values (:id, :chain, :mint, :source, :field, :status, :value, :reason, :observedAt)
            on conflict (chain, mint, source_id, field) do update
              set status = excluded.status, value = excluded.value,
                  unknown_reason = excluded.unknown_reason, observed_at = excluded.observed_at
            """;
    private static final String INSERT_SAMPLE = """
            insert into observation_sample
              (sample_id, chain, mint, source_id, field, status, value, unknown_reason, observed_at, bucket_start)
            values (:id, :chain, :mint, :source, :field, :status, :value, :reason, :observedAt, :bucket)
            on conflict on constraint observation_sample_bucket_uq do nothing
            """;
    private static final String UPDATE_CREATION = """
            update token set token_created_at = :created, sort_group = :group, sort_key = :key
            where chain = :chain and mint = :mint
            """;

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate batch;

    public JdbcMarketStore(JdbcClient jdbc, NamedParameterJdbcTemplate batch) {
        this.jdbc = jdbc;
        this.batch = batch;
    }

    @Override
    public void registerSource(SourceRef source, Duration interval) {
        jdbc.sql("""
                        insert into data_source (source_id, name, simulated, status, collection_interval_seconds, enabled)
                        values (:id, :name, :simulated, 'PENDING', :interval, true)
                        on conflict (source_id) do update
                          set name = excluded.name, simulated = excluded.simulated,
                              collection_interval_seconds = excluded.collection_interval_seconds, enabled = true
                        """)
                .param("id", source.sourceId())
                .param("name", source.name())
                .param("simulated", source.simulated())
                .param("interval", (int) interval.toSeconds())
                .update();
    }

    @Override
    public void disableSourcesOtherThan(Collection<String> enabledIds) {
        jdbc.sql("update data_source set enabled = false where enabled and not (source_id = any(:ids))")
                .param("ids", enabledIds.toArray(String[]::new))
                .update();
    }

    @Override
    public Optional<SourceState> sourceState(String sourceId) {
        return jdbc.sql("select * from data_source where source_id = :id")
                .param("id", sourceId)
                .query((rs, n) -> state(rs))
                .optional();
    }

    @Override
    public List<SourceStatus> sourceStatuses() {
        return jdbc.sql("select * from data_source where enabled order by source_id")
                .query((rs, n) -> state(rs).toStatus())
                .list();
    }

    @Override
    public void updateSourceStatus(
            String sourceId,
            SourceStatus.State status,
            Optional<FailureCategory> category,
            Instant attemptedAt,
            Optional<Instant> updatedAt) {
        jdbc.sql("""
                        update data_source
                        set status = :status, failure_category = :category, last_attempt_at = :attempted,
                            last_updated_at = coalesce(:updated, last_updated_at)
                        where source_id = :id
                        """)
                .param("status", status.name())
                .param("category", category.map(Enum::name).orElse(null))
                .param("attempted", Timestamp.from(attemptedAt))
                .param("updated", updatedAt.map(Timestamp::from).orElse(null), java.sql.Types.TIMESTAMP)
                .param("id", sourceId)
                .update();
    }

    @Override
    public void incrementStatusMark() {
        jdbc.sql("update source_status_mark set mark = mark + 1").update();
    }

    @Override
    public long statusMark() {
        return jdbc.sql("select mark from source_status_mark").query(Long.class).single();
    }

    @Override
    public void writeCollection(CollectionWrite write) {
        List<SqlParameterSource> tokens = new ArrayList<>();
        List<SqlParameterSource> observations = new ArrayList<>();
        List<SqlParameterSource> samples = new ArrayList<>();
        List<SqlParameterSource> creations = new ArrayList<>();
        for (TokenInfo token : write.tokens()) {
            tokens.add(tokenParams(token, write.seenAt()));
            Map<MarketField, ObservedValue> fields = write.values().getOrDefault(token.token(), Map.of());
            fields.forEach((field, observed) -> {
                MapSqlParameterSource params =
                        valueParams(token.token(), write.sourceId(), field, observed.value(), observed.observedAt());
                observations.add(params);
                if (field != MarketField.CREATED_AT) {
                    samples.add(sampleParams(params, observed.observedAt()));
                }
            });
            ObservedValue created = fields.get(MarketField.CREATED_AT);
            // U2: a real source sends only the fields renewed in its round; without CREATED_AT the stored creation
            // time (and the order columns) stay as they are. The simulated sources always send every field.
            if (created != null) {
                creations.add(creationParams(token.token(), MarketCollector.creationTime(created.value())));
            }
        }
        run(UPSERT_TOKEN, tokens);
        run(UPSERT_OBSERVATION, observations);
        run(INSERT_SAMPLE, samples);
        run(UPDATE_CREATION, creations);
    }

    @Override
    public void upsertToken(TokenInfo token, Instant seenAt) {
        batch.update(UPSERT_TOKEN, tokenParams(token, seenAt));
    }

    @Override
    public void upsertObservation(
            TokenId token, String sourceId, MarketField field, MarketValue value, Instant observedAt) {
        batch.update(UPSERT_OBSERVATION, valueParams(token, sourceId, field, value, observedAt));
    }

    @Override
    public void insertSample(TokenId token, String sourceId, MarketField field, MarketValue value, Instant observedAt) {
        batch.update(INSERT_SAMPLE, sampleParams(valueParams(token, sourceId, field, value, observedAt), observedAt));
    }

    @Override
    public void updateCreationTime(TokenId token, Optional<Instant> createdAt) {
        batch.update(UPDATE_CREATION, creationParams(token, createdAt));
    }

    /** Start of the 5-minute UTC bucket of an instant (the same as {@code date_bin('5 minutes', t, epoch)}). */
    public static Instant bucketStart(Instant observedAt) {
        long seconds = BUCKET.toSeconds();
        return Instant.ofEpochSecond(Math.floorDiv(observedAt.getEpochSecond(), seconds) * seconds);
    }

    /** Newest first in ascending order: the negated creation time in epoch microseconds (U1 order columns). */
    static long sortKey(Instant createdAt) {
        return -ChronoUnit.MICROS.between(Instant.EPOCH, createdAt);
    }

    private void run(String sql, List<SqlParameterSource> rows) {
        if (!rows.isEmpty()) {
            batch.batchUpdate(sql, rows.toArray(SqlParameterSource[]::new));
        }
    }

    private static MapSqlParameterSource tokenParams(TokenInfo token, Instant seenAt) {
        return new MapSqlParameterSource()
                .addValue("chain", token.token().chain())
                .addValue("mint", token.token().mint())
                .addValue("symbol", token.symbol())
                .addValue("name", token.name())
                .addValue("decimals", token.decimals())
                .addValue("seen", Timestamp.from(seenAt));
    }

    private static MapSqlParameterSource valueParams(
            TokenId token, String sourceId, MarketField field, MarketValue value, Instant observedAt) {
        return new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("chain", token.chain())
                .addValue("mint", token.mint())
                .addValue("source", sourceId)
                .addValue("field", field.name())
                .addValue("status", value.isKnown() ? "KNOWN" : "UNKNOWN")
                .addValue("value", value instanceof MarketValue.Known k ? k.value() : null, java.sql.Types.NUMERIC)
                .addValue(
                        "reason",
                        value instanceof MarketValue.Unknown u ? u.reason().name() : null,
                        java.sql.Types.VARCHAR)
                .addValue("observedAt", Timestamp.from(observedAt));
    }

    private static MapSqlParameterSource sampleParams(MapSqlParameterSource values, Instant observedAt) {
        MapSqlParameterSource sample = new MapSqlParameterSource(values.getValues());
        sample.registerSqlType("value", java.sql.Types.NUMERIC);
        sample.registerSqlType("reason", java.sql.Types.VARCHAR);
        return sample.addValue("id", UUID.randomUUID()).addValue("bucket", Timestamp.from(bucketStart(observedAt)));
    }

    private static MapSqlParameterSource creationParams(TokenId token, Optional<Instant> createdAt) {
        return new MapSqlParameterSource()
                .addValue("created", createdAt.map(Timestamp::from).orElse(null), java.sql.Types.TIMESTAMP)
                .addValue("group", createdAt.isPresent() ? 0 : 1)
                .addValue("key", createdAt.map(JdbcMarketStore::sortKey).orElse(0L))
                .addValue("chain", token.chain())
                .addValue("mint", token.mint());
    }

    private static SourceState state(ResultSet rs) throws SQLException {
        Timestamp updated = rs.getTimestamp("last_updated_at");
        Timestamp attempted = rs.getTimestamp("last_attempt_at");
        String category = rs.getString("failure_category");
        return new SourceState(
                new SourceRef(rs.getString("source_id"), rs.getString("name"), rs.getBoolean("simulated")),
                SourceStatus.State.valueOf(rs.getString("status")),
                Optional.ofNullable(category).map(FailureCategory::valueOf),
                Optional.ofNullable(updated).map(Timestamp::toInstant),
                Optional.ofNullable(attempted).map(Timestamp::toInstant));
    }
}
