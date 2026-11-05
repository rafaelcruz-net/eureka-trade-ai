package com.memetrade.decision.infra;

import com.memetrade.decision.domain.DecisionOutcome;
import com.memetrade.decision.domain.StrategyId;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The {@code decision} table of the DecisionEngine (logical-components.md): one immutable row per cycle with factors,
 * inputs and context as JSONB (BR4.10). Every read carries tenant and person (NFR5.1); the list is by cursor on
 * (bot, creation descending, decision) and reads the JSONB only for the page (performance-design.md).
 */
@Repository
public class JdbcDecisionRepository {

    private static final String SELECT = """
            select decision_id, bot_id, cycle_id, chain, token_mint, action, no_trade_reason, exit_reason,
                   requested_quantity, strategy_template_id, strategy_version, limits_version, factors::text as factors,
                   inputs::text as inputs, summary, valid_until, created_at
            from decision""";

    private final JdbcClient jdbc;

    public JdbcDecisionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** What the cycle records (BR4.10). */
    public record NewDecision(
            UUID decisionId,
            UUID cycleId,
            UUID botId,
            UUID tenantId,
            UUID ownerUserId,
            StrategyId strategy,
            String strategyVersion,
            long limitsVersion,
            DecisionOutcome outcome,
            DecisionJson.Recorded recorded,
            Instant createdAt) {}

    /** One recorded decision, as the routes and the summary read it. */
    public record StoredDecision(
            UUID decisionId,
            UUID botId,
            UUID cycleId,
            Optional<String> chain,
            Optional<String> tokenMint,
            String action,
            Optional<String> noTradeReason,
            Optional<String> exitReason,
            Optional<BigDecimal> requestedQuantity,
            String strategyVersion,
            long limitsVersion,
            String factors,
            String inputs,
            String summary,
            Instant validUntil,
            Instant createdAt) {}

    public record DecisionPage(List<StoredDecision> items, Optional<String> next) {}

    public void insert(NewDecision decision) {
        DecisionOutcome outcome = decision.outcome();
        jdbc.sql("""
                        insert into decision (decision_id, bot_id, cycle_id, tenant_id, owner_user_id, mode, chain,
                            token_mint, action, no_trade_reason, exit_reason, requested_quantity,
                            strategy_template_id, strategy_version, parameters, limits_version, factors, inputs,
                            context, summary, valid_until, created_at)
                        values (:decision, :bot, :cycle, :tenant, :owner, 'PAPER', :chain, :mint, :action, :reason,
                                :exit, :quantity, :strategy, :version, cast(:parameters as jsonb), :limits,
                                cast(:factors as jsonb), cast(:inputs as jsonb), cast(:context as jsonb), :summary,
                                :validUntil, :created)
                        """)
                .param("decision", decision.decisionId())
                .param("bot", decision.botId())
                .param("cycle", decision.cycleId())
                .param("tenant", decision.tenantId())
                .param("owner", decision.ownerUserId())
                .param("chain", outcome.token().map(t -> t.chain()).orElse(null), java.sql.Types.VARCHAR)
                .param("mint", outcome.token().map(t -> t.mint()).orElse(null), java.sql.Types.VARCHAR)
                .param("action", outcome.action().name())
                .param("reason", outcome.noTradeReason().map(Enum::name).orElse(null), java.sql.Types.VARCHAR)
                .param("exit", outcome.exitReason().map(Enum::name).orElse(null), java.sql.Types.VARCHAR)
                .param("quantity", outcome.requestedQuantity().orElse(null), java.sql.Types.NUMERIC)
                .param("strategy", decision.strategy().name())
                .param("version", decision.strategyVersion())
                .param("parameters", decision.recorded().parameters())
                .param("limits", decision.limitsVersion())
                .param("factors", decision.recorded().factors())
                .param("inputs", decision.recorded().inputs())
                .param("context", decision.recorded().context())
                .param("summary", outcome.summary())
                .param("validUntil", Timestamp.from(outcome.validUntil()))
                .param("created", Timestamp.from(decision.createdAt()))
                .update();
    }

    /** C4 {@code GET /bots/{botId}/decisions}: the decisions of a bot of the person, newest first, by cursor. */
    public DecisionPage page(UUID botId, UUID tenantId, UUID ownerUserId, Optional<String> cursor, int limit) {
        Optional<Cursor> after = cursor.map(Cursor::decode);
        String sql = SELECT + " where bot_id = :bot and tenant_id = :tenant and owner_user_id = :owner"
                + (after.isPresent() ? " and (created_at, decision_id) < (:afterCreated, :afterId)" : "")
                + " order by created_at desc, decision_id desc limit :limit";
        var statement = jdbc.sql(sql)
                .param("bot", botId)
                .param("tenant", tenantId)
                .param("owner", ownerUserId)
                .param("limit", limit + 1);
        if (after.isPresent()) {
            statement = statement
                    .param("afterCreated", Timestamp.from(after.get().createdAt()))
                    .param("afterId", after.get().decisionId());
        }
        List<StoredDecision> rows = statement.query(JdbcDecisionRepository::map).list();
        if (rows.size() <= limit) {
            return new DecisionPage(rows, Optional.empty());
        }
        List<StoredDecision> page = List.copyOf(rows.subList(0, limit));
        StoredDecision last = page.getLast();
        return new DecisionPage(page, Optional.of(new Cursor(last.createdAt(), last.decisionId()).encode()));
    }

    /** C16 summary: the last decision of any bot of the person. */
    public Optional<StoredDecision> last(UUID tenantId, UUID ownerUserId) {
        return jdbc.sql(SELECT + " where tenant_id = :tenant and owner_user_id = :owner"
                        + " order by created_at desc, decision_id desc limit 1")
                .param("tenant", tenantId)
                .param("owner", ownerUserId)
                .query(JdbcDecisionRepository::map)
                .optional();
    }

    public long count(UUID botId) {
        return jdbc.sql("select count(*) from decision where bot_id = :bot")
                .param("bot", botId)
                .query(Long.class)
                .single();
    }

    /** The number of deleted decisions and cycles of one purge batch. */
    public record Purged(long decisions, long cycles) {}

    /**
     * BR9.1: one batch of HOLD and NO_TRADE decisions created before {@code cutoff}, deleted with their cycles in one
     * statement (BUY and SELL never: only they become orders, so no rejection nor order loses its reference).
     */
    public Purged purgeBatch(Instant cutoff, int batchSize) {
        return jdbc.sql("""
                        with gone as (
                            delete from decision where decision_id in (
                                select decision_id from decision
                                where action in ('HOLD', 'NO_TRADE') and created_at < :cutoff
                                limit :batch)
                            returning cycle_id),
                        cycles as (
                            delete from bot_cycle where cycle_id in (select cycle_id from gone) returning 1)
                        select (select count(*) from gone) as decisions, (select count(*) from cycles) as cycles
                        """)
                .param("cutoff", Timestamp.from(cutoff))
                .param("batch", batchSize)
                .query((rs, row) -> new Purged(rs.getLong("decisions"), rs.getLong("cycles")))
                .single();
    }

    private static StoredDecision map(ResultSet rs, int row) throws SQLException {
        return new StoredDecision(
                rs.getObject("decision_id", UUID.class),
                rs.getObject("bot_id", UUID.class),
                rs.getObject("cycle_id", UUID.class),
                Optional.ofNullable(rs.getString("chain")),
                Optional.ofNullable(rs.getString("token_mint")),
                rs.getString("action"),
                Optional.ofNullable(rs.getString("no_trade_reason")),
                Optional.ofNullable(rs.getString("exit_reason")),
                Optional.ofNullable(rs.getBigDecimal("requested_quantity")).map(BigDecimal::stripTrailingZeros),
                rs.getString("strategy_version"),
                rs.getLong("limits_version"),
                rs.getString("factors"),
                rs.getString("inputs"),
                rs.getString("summary"),
                rs.getTimestamp("valid_until").toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    /** The opaque keyset cursor of the list (creation instant and decision id, base64url). */
    record Cursor(Instant createdAt, UUID decisionId) {
        String encode() {
            String raw = createdAt.getEpochSecond() + "." + createdAt.getNano() + ":" + decisionId;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String cursor) {
            try {
                String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                String[] parts = raw.split(":", 2);
                String[] seconds = parts[0].split("\\.", 2);
                return new Cursor(
                        Instant.ofEpochSecond(Long.parseLong(seconds[0]), Long.parseLong(seconds[1])),
                        UUID.fromString(parts[1]));
            } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException invalid) {
                throw new InvalidDecisionCursorException();
            }
        }
    }

    /** A cursor this repository did not issue; the route answers 400 VALIDATION_FAILED on {@code cursor}. */
    public static final class InvalidDecisionCursorException extends RuntimeException {
        InvalidDecisionCursorException() {
            super("Invalid cursor");
        }
    }
}
