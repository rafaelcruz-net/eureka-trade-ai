package com.memetrade.riskassessment.infra;

import com.memetrade.marketdata.api.TokenId;
import com.memetrade.riskassessment.api.DecisionHint;
import com.memetrade.riskassessment.api.RiskBand;
import com.memetrade.riskassessment.api.UnavailableReason;
import com.memetrade.riskassessment.domain.RiskComputation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
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
 * {@code risk_assessment}: only the latest assessment per token (Q8). A write replaces the stored one only when its
 * weight version is greater than or equal to the stored version, in the {@code ON CONFLICT} clause itself, so an older
 * version never overwrites a newer one, whatever the timing (NFR10.8).
 */
@Repository
public class JdbcRiskAssessmentStore {

    private static final String UPSERT = """
            insert into risk_assessment (chain, mint, assessment_id, weight_set_version, status, score, band,
              band_raised_by_unknown, unavailable_reason, coverage, decision_hint, factors, inputs_fingerprint, computed_at)
            values (:chain, :mint, :id, :version, :status, :score, :band, :raised, :reason, :coverage, :hint,
              cast(:factors as jsonb), :fingerprint, :computedAt)
            on conflict (chain, mint) do update
              set assessment_id = excluded.assessment_id, weight_set_version = excluded.weight_set_version,
                  status = excluded.status, score = excluded.score, band = excluded.band,
                  band_raised_by_unknown = excluded.band_raised_by_unknown,
                  unavailable_reason = excluded.unavailable_reason, coverage = excluded.coverage,
                  decision_hint = excluded.decision_hint, factors = excluded.factors,
                  inputs_fingerprint = excluded.inputs_fingerprint, computed_at = excluded.computed_at
              where risk_assessment.weight_set_version <= excluded.weight_set_version
            """;

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate batch;

    public JdbcRiskAssessmentStore(JdbcClient jdbc, NamedParameterJdbcTemplate batch) {
        this.jdbc = jdbc;
        this.batch = batch;
    }

    /** One assessment to write: its computation, inputs fingerprint and computation time. */
    public record Write(TokenId token, RiskComputation computation, String fingerprint, Instant computedAt) {}

    /** What a stored assessment depends on, to skip a rewrite when nothing changed (BR3.7). */
    public record Stamp(int version, String fingerprint) {}

    /** A stored assessment as read by the detail and by C12. */
    public record Stored(
            TokenId token,
            int version,
            boolean available,
            Optional<Integer> score,
            Optional<RiskBand> band,
            boolean bandRaisedByUnknown,
            Optional<UnavailableReason> unavailableReason,
            java.math.BigDecimal coverage,
            DecisionHint decisionHint,
            String factorsJson,
            String fingerprint,
            Instant computedAt) {}

    public Map<TokenId, Stamp> stamps() {
        Map<TokenId, Stamp> stamps = new HashMap<>();
        jdbc.sql("select chain, mint, weight_set_version, inputs_fingerprint from risk_assessment")
                .query(rs -> {
                    stamps.put(
                            new TokenId(rs.getString("chain"), rs.getString("mint")),
                            new Stamp(rs.getInt("weight_set_version"), rs.getString("inputs_fingerprint")));
                });
        return stamps;
    }

    public Optional<Stored> find(TokenId token) {
        return jdbc.sql("select * from risk_assessment where chain = :chain and mint = :mint")
                .param("chain", token.chain())
                .param("mint", token.mint())
                .query((rs, n) -> stored(rs))
                .optional();
    }

    /** Writes a batch in the caller's transaction; returns how many rows were actually replaced or inserted. */
    public int write(List<Write> writes) {
        if (writes.isEmpty()) {
            return 0;
        }
        int[] counts = batch.batchUpdate(
                UPSERT, writes.stream().map(JdbcRiskAssessmentStore::params).toArray(SqlParameterSource[]::new));
        int written = 0;
        for (int count : counts) {
            written += Math.max(count, 0);
        }
        return written;
    }

    private static SqlParameterSource params(Write write) {
        RiskComputation c = write.computation();
        return new MapSqlParameterSource()
                .addValue("chain", write.token().chain())
                .addValue("mint", write.token().mint())
                .addValue("id", UUID.randomUUID())
                .addValue("version", c.weightSetVersion())
                .addValue("status", c.available() ? "AVAILABLE" : "UNAVAILABLE")
                .addValue("score", c.score().orElse(null), java.sql.Types.INTEGER)
                .addValue("band", c.band().map(Enum::name).orElse(null), java.sql.Types.VARCHAR)
                .addValue("raised", c.bandRaisedByUnknown())
                .addValue("reason", c.unavailableReason().map(Enum::name).orElse(null), java.sql.Types.VARCHAR)
                .addValue("coverage", c.coverage())
                .addValue("hint", c.decisionHint().name())
                .addValue("factors", RiskJson.writeResults(c.factors()))
                .addValue("fingerprint", write.fingerprint())
                .addValue("computedAt", Timestamp.from(write.computedAt()));
    }

    private static Stored stored(ResultSet rs) throws SQLException {
        int score = rs.getInt("score");
        boolean hasScore = !rs.wasNull();
        return new Stored(
                new TokenId(rs.getString("chain"), rs.getString("mint")),
                rs.getInt("weight_set_version"),
                "AVAILABLE".equals(rs.getString("status")),
                hasScore ? Optional.of(score) : Optional.empty(),
                Optional.ofNullable(rs.getString("band")).map(RiskBand::valueOf),
                rs.getBoolean("band_raised_by_unknown"),
                Optional.ofNullable(rs.getString("unavailable_reason")).map(UnavailableReason::valueOf),
                rs.getBigDecimal("coverage"),
                DecisionHint.valueOf(rs.getString("decision_hint")),
                rs.getString("factors"),
                rs.getString("inputs_fingerprint"),
                rs.getTimestamp("computed_at").toInstant());
    }
}
