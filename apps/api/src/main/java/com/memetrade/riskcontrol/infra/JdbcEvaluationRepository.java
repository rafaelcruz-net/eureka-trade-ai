package com.memetrade.riskcontrol.infra;

import com.memetrade.riskcontrol.api.OrderSide;
import com.memetrade.riskcontrol.domain.EvaluationRecord;
import com.memetrade.riskcontrol.domain.EvaluationRecord.Outcome;
import com.memetrade.riskcontrol.domain.EvaluationRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code order_evaluation} through the main pool, in the caller's (cycle) transaction. Parameterised statements only;
 * every query carries the tenant and {@code mode = 'PAPER'} (NFR5.1, NFR5.3) and is served by the unique index
 * (tenant_id, mode, bot_id, decision_id) (performance-design.md).
 */
@Repository
public class JdbcEvaluationRepository implements EvaluationRepository {

    private static final String INSERT = """
            insert into order_evaluation (evaluation_id, tenant_id, owner_user_id, mode, bot_id, decision_id,
                side, chain, token_mint, requested_quantity, limits_version, outcome, evaluated_at)
            values (:id, :tenant, :owner, 'PAPER', :bot, :decision, :side, :chain, :mint, :quantity,
                :version, :outcome, :at)
            """;

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate batch;

    public JdbcEvaluationRepository(JdbcClient jdbc, NamedParameterJdbcTemplate batch) {
        this.jdbc = jdbc;
        this.batch = batch;
    }

    @Override
    public void insertAll(java.util.List<EvaluationRecord> evaluations) {
        batch.batchUpdate(
                INSERT,
                evaluations.stream().map(JdbcEvaluationRepository::parameters).toArray(SqlParameterSource[]::new));
    }

    private static SqlParameterSource parameters(EvaluationRecord evaluation) {
        return new MapSqlParameterSource()
                .addValue("id", evaluation.evaluationId())
                .addValue("tenant", evaluation.tenantId())
                .addValue("owner", evaluation.ownerUserId())
                .addValue("bot", evaluation.botId())
                .addValue("decision", evaluation.decisionId())
                .addValue("side", evaluation.side().name())
                .addValue("chain", evaluation.chain())
                .addValue("mint", evaluation.tokenMint())
                .addValue("quantity", evaluation.requestedQuantity())
                .addValue("version", evaluation.limitsVersion())
                .addValue("outcome", evaluation.outcome().name())
                .addValue("at", Timestamp.from(evaluation.evaluatedAt()));
    }

    /**
     * {@code INSERT ... ON CONFLICT DO NOTHING RETURNING}: a conflicting decision returns no row instead of an error,
     * so the transaction is never aborted (BR2.8); a concurrent uncommitted insert of the same decision makes this
     * statement wait for its end.
     */
    @Override
    public boolean insertIfAbsent(EvaluationRecord evaluation) {
        return jdbc.sql(INSERT + """
                        on conflict (tenant_id, mode, bot_id, decision_id) do nothing
                        returning evaluation_id
                        """)
                .paramSource(parameters(evaluation))
                .query(UUID.class)
                .optional()
                .isPresent();
    }

    @Override
    public Optional<EvaluationRecord> find(UUID tenantId, UUID botId, UUID decisionId) {
        return jdbc.sql("""
                        select * from order_evaluation
                        where tenant_id = :tenant and mode = 'PAPER' and bot_id = :bot and decision_id = :decision
                        """)
                .param("tenant", tenantId)
                .param("bot", botId)
                .param("decision", decisionId)
                .query(JdbcEvaluationRepository::map)
                .optional();
    }

    /** The EXISTS of BR4.1 on the prefix (tenant, mode, bot) of the unique index, filtered by the person. */
    @Override
    public boolean existsForPerson(UUID tenantId, UUID ownerUserId, UUID botId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        select exists (
                            select 1 from order_evaluation
                            where tenant_id = :tenant and mode = 'PAPER' and bot_id = :bot
                              and owner_user_id = :owner)
                        """)
                .param("tenant", tenantId)
                .param("bot", botId)
                .param("owner", ownerUserId)
                .query(Boolean.class)
                .single());
    }

    private static EvaluationRecord map(ResultSet rs, int row) throws SQLException {
        return new EvaluationRecord(
                rs.getObject("evaluation_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("owner_user_id", UUID.class),
                rs.getObject("bot_id", UUID.class),
                rs.getObject("decision_id", UUID.class),
                OrderSide.valueOf(rs.getString("side")),
                rs.getString("chain"),
                rs.getString("token_mint"),
                rs.getBigDecimal("requested_quantity"),
                rs.getLong("limits_version"),
                Outcome.valueOf(rs.getString("outcome")),
                rs.getTimestamp("evaluated_at").toInstant());
    }
}
