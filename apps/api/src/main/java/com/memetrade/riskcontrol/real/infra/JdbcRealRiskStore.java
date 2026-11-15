package com.memetrade.riskcontrol.real.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.riskcontrol.api.BlockedToken;
import com.memetrade.riskcontrol.api.GateDecision;
import com.memetrade.riskcontrol.api.OrderIntent;
import com.memetrade.riskcontrol.api.RealRejectionCode;
import com.memetrade.riskcontrol.api.RealRejectionDetail;
import com.memetrade.riskcontrol.real.domain.DaySnapshot;
import com.memetrade.riskcontrol.real.domain.RealVerdict;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 261113-trading-real-bot U3 (logical-components.md JdbcRealRiskStore, U3-NFR3.6, U3-NFR4.2, U3-NFR6.1): the four tables
 * of the barrier in the transaction of the caller. Evaluations and reservations are written with
 * {@code ON CONFLICT (decision_id) DO NOTHING}, the photo with {@code ON CONFLICT DO NOTHING}; every read of a tenant's
 * data filters by tenant. The block list is global. Never reads a table of another module.
 */
@Repository
public class JdbcRealRiskStore {

    private static final TypeReference<LinkedHashMap<String, Long>> FIGURES = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcRealRiskStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** One evaluation to record (BR7.1). */
    public record EvaluationRow(
            UUID evaluationId,
            OrderIntent intent,
            RealVerdict verdict,
            Optional<UUID> approvalId,
            Instant evaluatedAt) {
        public EvaluationRow {
            Objects.requireNonNull(evaluationId, "evaluationId");
            Objects.requireNonNull(intent, "intent");
            Objects.requireNonNull(verdict, "verdict");
            Objects.requireNonNull(approvalId, "approvalId");
            Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        }
    }

    /** One reservation of an approved buy (BR6.2). */
    public record ReservationRow(
            UUID approvalId,
            UUID tenantId,
            UUID userId,
            UUID botWalletId,
            UUID botId,
            UUID decisionId,
            String mint,
            long inputLamports,
            Instant createdAt,
            Instant expiresAt) {}

    /** A reservation as read back. */
    public record Reservation(
            UUID approvalId, UUID tenantId, UUID userId, UUID botWalletId, String state, Instant expiresAt) {
        public boolean open() {
            return "OPEN".equals(state);
        }
    }

    /** BR6.1: the decision recorded for {@code decisionId} in this tenant, approved or rejected. */
    public Optional<GateDecision> decisionOf(UUID tenantId, UUID decisionId) {
        return jdbc.sql("""
                        select evaluation_id, outcome, code, detail, detail_mint, figures::text as figures, approval_id
                        from real_risk_evaluation where tenant_id = :tenant and decision_id = :decision""")
                .param("tenant", tenantId)
                .param("decision", decisionId)
                .query(this::decision)
                .optional();
    }

    /** @return {@code false} when the decision already had an evaluation (nothing written) */
    public boolean insertEvaluation(EvaluationRow row) {
        OrderIntent intent = row.intent();
        RealVerdict verdict = row.verdict();
        return jdbc.sql("""
                                insert into real_risk_evaluation (evaluation_id, tenant_id, user_id, bot_id, decision_id,
                                  origin, side, chain, mint, input_lamports, token_base_units, outcome, code, detail,
                                  detail_mint, figures, approval_id, evaluated_at, evaluated_day)
                                values (:id, :tenant, :user, :bot, :decision, :origin, :side, :chain, :mint, :input,
                                  :units, :outcome, :code, :detail, :detailMint, cast(:figures as jsonb), :approval, :at,
                                  :day)
                                on conflict (decision_id) do nothing""")
                        .param("id", row.evaluationId())
                        .param("tenant", intent.tenantId())
                        .param("user", intent.userId())
                        .param("bot", intent.botId())
                        .param("decision", intent.decisionId())
                        .param("origin", intent.origin().name())
                        .param("side", intent.side().name())
                        .param("chain", intent.chain())
                        .param("mint", intent.mint())
                        .param("input", intent.inputLamports())
                        .param("units", intent.tokenBaseUnits())
                        .param("outcome", verdict.approved() ? "APPROVED" : "REJECTED")
                        .param(
                                "code",
                                verdict.approved() ? null : verdict.code().name())
                        .param(
                                "detail",
                                verdict.approved() ? null : verdict.detail().name())
                        .param("detailMint", verdict.detailMint().orElse(null))
                        .param("figures", figuresJson(verdict.figures()))
                        .param("approval", row.approvalId().orElse(null))
                        .param("at", Timestamp.from(row.evaluatedAt()))
                        .param("day", LocalDate.ofInstant(row.evaluatedAt(), ZoneOffset.UTC))
                        .update()
                == 1;
    }

    /** @return {@code false} when the decision already had a reservation */
    public boolean insertReservation(ReservationRow row) {
        return jdbc.sql("""
                                insert into real_risk_reservation (approval_id, tenant_id, user_id, bot_wallet_id,
                                  bot_id, decision_id, mint, input_lamports, state, created_at, expires_at)
                                values (:id, :tenant, :user, :wallet, :bot, :decision, :mint, :input, 'OPEN', :created,
                                  :expires)
                                on conflict (decision_id) do nothing""")
                        .param("id", row.approvalId())
                        .param("tenant", row.tenantId())
                        .param("user", row.userId())
                        .param("wallet", row.botWalletId())
                        .param("bot", row.botId())
                        .param("decision", row.decisionId())
                        .param("mint", row.mint())
                        .param("input", row.inputLamports())
                        .param("created", Timestamp.from(row.createdAt()))
                        .param("expires", Timestamp.from(row.expiresAt()))
                        .update()
                == 1;
    }

    /** BR5.1: the lamports held by the OPEN reservations of the barrier for this wallet. */
    public long openReservedLamports(UUID tenantId, UUID botWalletId) {
        return jdbc.sql("""
                        select coalesce(sum(input_lamports), 0) from real_risk_reservation
                        where tenant_id = :tenant and bot_wallet_id = :wallet and state = 'OPEN'""")
                .param("tenant", tenantId)
                .param("wallet", botWalletId)
                .query(Long.class)
                .single();
    }

    /** BR4.2: the approved buys of the bot on the UTC day, those that later failed, went ambiguous or expired included. */
    public int approvedBuys(UUID tenantId, UUID botId, LocalDate day) {
        return jdbc.sql("""
                        select count(*) from real_risk_evaluation
                        where bot_id = :bot and evaluated_day = :day and side = 'BUY' and outcome = 'APPROVED'
                          and tenant_id = :tenant""")
                .param("bot", botId)
                .param("day", day)
                .param("tenant", tenantId)
                .query(Integer.class)
                .single();
    }

    public Optional<DaySnapshot> snapshot(UUID tenantId, UUID botWalletId, LocalDate day) {
        return jdbc.sql("""
                        select tenant_id, bot_wallet_id, day, start_value_lamports, start_cap_lamports, taken_at, taken_by
                        from real_risk_day where bot_wallet_id = :wallet and day = :day and tenant_id = :tenant""")
                .param("wallet", botWalletId)
                .param("day", day)
                .param("tenant", tenantId)
                .query((rs, row) -> new DaySnapshot(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("bot_wallet_id", UUID.class),
                        rs.getObject("day", LocalDate.class),
                        rs.getLong("start_value_lamports"),
                        rs.getLong("start_cap_lamports"),
                        rs.getTimestamp("taken_at").toInstant(),
                        DaySnapshot.TakenBy.valueOf(rs.getString("taken_by"))))
                .optional();
    }

    /** BR4.4: the first photo of the day stays; a later one changes nothing ({@code false}). */
    public boolean insertSnapshot(DaySnapshot snapshot) {
        return jdbc.sql("""
                                insert into real_risk_day (tenant_id, bot_wallet_id, day, start_value_lamports,
                                  start_cap_lamports, taken_at, taken_by)
                                values (:tenant, :wallet, :day, :value, :cap, :at, :by)
                                on conflict do nothing""")
                        .param("tenant", snapshot.tenantId())
                        .param("wallet", snapshot.botWalletId())
                        .param("day", snapshot.day())
                        .param("value", snapshot.startValueLamports())
                        .param("cap", snapshot.startCapLamports())
                        .param("at", Timestamp.from(snapshot.takenAt()))
                        .param("by", snapshot.takenBy().name())
                        .update()
                == 1;
    }

    public boolean isBlocked(String chain, String mint) {
        return Boolean.TRUE.equals(
                jdbc.sql("select exists (select 1 from real_blocked_token where chain = :chain and mint = :mint)")
                        .param("chain", chain)
                        .param("mint", mint)
                        .query(Boolean.class)
                        .single());
    }

    public Optional<BlockedToken> blocked(String chain, String mint) {
        return jdbc.sql("select chain, mint, reason, blocked_by, blocked_at from real_blocked_token "
                        + "where chain = :chain and mint = :mint")
                .param("chain", chain)
                .param("mint", mint)
                .query(JdbcRealRiskStore::blockedToken)
                .optional();
    }

    /** BR2.3: {@code false} when the token was already blocked (the row stays as it was). */
    public boolean block(BlockedToken token) {
        return jdbc.sql("""
                                insert into real_blocked_token (chain, mint, reason, blocked_by, blocked_at)
                                values (:chain, :mint, :reason, :by, :at) on conflict do nothing""")
                        .param("chain", token.chain())
                        .param("mint", token.mint())
                        .param("reason", token.reason())
                        .param("by", token.blockedBy())
                        .param("at", Timestamp.from(token.blockedAt()))
                        .update()
                == 1;
    }

    /** BR2.3: the removed row, empty when the token was not blocked. */
    public Optional<BlockedToken> unblock(String chain, String mint) {
        return jdbc.sql("delete from real_blocked_token where chain = :chain and mint = :mint "
                        + "returning chain, mint, reason, blocked_by, blocked_at")
                .param("chain", chain)
                .param("mint", mint)
                .query(JdbcRealRiskStore::blockedToken)
                .optional();
    }

    public Optional<Reservation> reservation(UUID tenantId, UUID approvalId) {
        return jdbc.sql("""
                        select approval_id, tenant_id, user_id, bot_wallet_id, state, expires_at
                        from real_risk_reservation where approval_id = :id and tenant_id = :tenant""")
                .param("id", approvalId)
                .param("tenant", tenantId)
                .query((rs, row) -> new Reservation(
                        rs.getObject("approval_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("bot_wallet_id", UUID.class),
                        rs.getString("state"),
                        rs.getTimestamp("expires_at").toInstant()))
                .optional();
    }

    /** BR6.2: OPEN to HANDED_OVER or EXPIRED; {@code false} when it was no longer OPEN. */
    public boolean closeReservation(UUID approvalId, String state, Instant at) {
        return jdbc.sql("update real_risk_reservation set state = :state, closed_at = :at "
                                + "where approval_id = :id and state = 'OPEN'")
                        .param("state", state)
                        .param("at", Timestamp.from(at))
                        .param("id", approvalId)
                        .update()
                == 1;
    }

    /** F5: every OPEN reservation past its expiry becomes EXPIRED; returns how many. */
    public int expireReservations(Instant now) {
        return jdbc.sql("update real_risk_reservation set state = 'EXPIRED', closed_at = :now "
                        + "where state = 'OPEN' and expires_at <= :now")
                .param("now", Timestamp.from(now))
                .update();
    }

    /** U3-NFR5.4: the reservations by state, for the gauge of the worker (every state present, zero included). */
    public Map<String, Long> reservationsByState() {
        Map<String, Long> counts = new TreeMap<>(Map.of("OPEN", 0L, "HANDED_OVER", 0L, "EXPIRED", 0L));
        jdbc.sql("select state, count(*) as n from real_risk_reservation group by state")
                .query(rs -> {
                    counts.put(rs.getString("state"), rs.getLong("n"));
                });
        return counts;
    }

    private GateDecision decision(ResultSet rs, int row) throws SQLException {
        UUID evaluationId = rs.getObject("evaluation_id", UUID.class);
        if ("APPROVED".equals(rs.getString("outcome"))) {
            return new GateDecision.Approved(
                    evaluationId, Optional.ofNullable(rs.getObject("approval_id", UUID.class)));
        }
        return new GateDecision.Rejected(
                evaluationId,
                RealRejectionCode.valueOf(rs.getString("code")),
                RealRejectionDetail.valueOf(rs.getString("detail")),
                figures(rs.getString("figures")),
                Optional.ofNullable(rs.getString("detail_mint")));
    }

    private Map<String, Long> figures(String text) {
        try {
            return json.readValue(text, FIGURES);
        } catch (JsonProcessingException unreadable) {
            throw new IllegalStateException("The figures of a REAL evaluation are not a map of integers", unreadable);
        }
    }

    private String figuresJson(Map<String, Long> figures) {
        try {
            return json.writeValueAsString(figures);
        } catch (JsonProcessingException unwritable) {
            throw new IllegalStateException("The figures of a REAL evaluation could not be written", unwritable);
        }
    }

    private static BlockedToken blockedToken(ResultSet rs, int row) throws SQLException {
        return new BlockedToken(
                rs.getString("chain"),
                rs.getString("mint"),
                rs.getString("reason"),
                rs.getObject("blocked_by", UUID.class),
                rs.getTimestamp("blocked_at").toInstant());
    }
}
