package com.memetrade.realexecution.infra;

import com.memetrade.realexecution.api.CancelScope;
import com.memetrade.realexecution.api.RealFailureCode;
import com.memetrade.realexecution.api.RealOrderState;
import com.memetrade.realexecution.api.RealSide;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JDBC adapter of the tables of the REAL execution (migration 0019). Every query of a person filters by tenant and
 * person; the jobs read tenant and person FROM THE ROW they handle. Every transition is a compare-and-set on the
 * expected state ({@link #update}); the lease uses the clock of the database ({@code now()}), so instances never
 * depend on their local clocks (reliability-design.md).
 */
@Repository
public class JdbcRealExecutionStore {

    static final String ORDER_COLUMNS = """
            real_order_id, tenant_id, user_id, bot_id, liquidation_id, decision_request_id, origin, idempotency_key, side,
            mint, input_lamports, input_base_units, operation_mode, signing_mode, max_slippage_bps, reason, state,
            failure_code, quote_at, expected_out, min_out, requoted, signing_deadline, signing_claimed_at,
            signing_requested_at, message_digest, unsigned_message, signed_transaction, tx_signature,
            last_valid_block_height, sent_at, out_base_units, fee_lamports, fee_payer, created_at, updated_at,
            bot_wallet_id, rent_lamports""";

    private final JdbcClient jdbc;

    public JdbcRealExecutionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ----- decisions (real_decision_request) -----

    /** A decision of the cycle (M6 recordDecision). */
    public record DecisionRow(
            UUID decisionRequestId,
            UUID tenantId,
            UUID userId,
            UUID botId,
            UUID decisionId,
            Optional<UUID> approvalId,
            RealSide side,
            String mint,
            Long inputLamports,
            Long tokenBaseUnits,
            String reason,
            Instant createdAt) {}

    /** Inserts the decision once per decisionId; returns the id recorded (the first one). */
    public UUID insertDecision(DecisionRow row) {
        jdbc.sql("""
                        insert into real_decision_request (decision_request_id, tenant_id, user_id, bot_id, decision_id,
                          approval_id, side, mint, input_lamports, token_base_units, reason, created_at)
                        values (:id, :tenant, :user, :bot, :decision, :approval, :side, :mint, :lamports, :units,
                          :reason, :at)
                        on conflict (decision_id) do nothing""")
                .param("id", row.decisionRequestId())
                .param("tenant", row.tenantId())
                .param("user", row.userId())
                .param("bot", row.botId())
                .param("decision", row.decisionId())
                .param("approval", row.approvalId().orElse(null))
                .param("side", row.side().name())
                .param("mint", row.mint())
                .param("lamports", row.inputLamports())
                .param("units", row.tokenBaseUnits())
                .param("reason", row.reason())
                .param("at", Timestamp.from(row.createdAt()))
                .update();
        return jdbc.sql("select decision_request_id from real_decision_request where decision_id = :decision"
                        + " and tenant_id = :tenant")
                .param("decision", row.decisionId())
                .param("tenant", row.tenantId())
                .query(UUID.class)
                .single();
    }

    public List<UUID> pendingDecisions(int limit) {
        return jdbc.sql("select decision_request_id from real_decision_request where state = 'PENDING'"
                        + " order by created_at limit :limit")
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    /** Locks one PENDING decision for the rest of the transaction; empty when another consumer holds or closed it. */
    public Optional<DecisionRow> lockPendingDecision(UUID decisionRequestId) {
        return jdbc.sql("""
                        select * from real_decision_request
                        where decision_request_id = :id and state = 'PENDING'
                        for update skip locked""")
                .param("id", decisionRequestId)
                .query(JdbcRealExecutionStore::decision)
                .optional();
    }

    public void closeDecision(UUID decisionRequestId, String state, String discardReason, Instant now) {
        jdbc.sql("""
                        update real_decision_request set state = :state, discard_reason = :reason, processed_at = :at
                        where decision_request_id = :id and state = 'PENDING'""")
                .param("id", decisionRequestId)
                .param("state", state)
                .param("reason", discardReason)
                .param("at", Timestamp.from(now))
                .update();
    }

    /** The created_at of a decision request, for the wait of the queue (U4-NFR5.8). */
    public Optional<Instant> decisionCreatedAt(UUID decisionRequestId) {
        return jdbc.sql("select created_at from real_decision_request where decision_request_id = :id")
                .param("id", decisionRequestId)
                .query(Timestamp.class)
                .optional()
                .map(Timestamp::toInstant);
    }

    private static DecisionRow decision(ResultSet rs, int row) throws SQLException {
        return new DecisionRow(
                rs.getObject("decision_request_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("bot_id", UUID.class),
                rs.getObject("decision_id", UUID.class),
                Optional.ofNullable(rs.getObject("approval_id", UUID.class)),
                RealSide.valueOf(rs.getString("side")),
                rs.getString("mint"),
                nullableLong(rs, "input_lamports"),
                nullableLong(rs, "token_base_units"),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant());
    }

    // ----- orders (real_order) -----

    /** One row of real_order. */
    public record OrderRow(
            UUID realOrderId,
            UUID tenantId,
            UUID userId,
            UUID botId,
            UUID liquidationId,
            UUID decisionRequestId,
            String origin,
            String idempotencyKey,
            RealSide side,
            String mint,
            Long inputLamports,
            Long inputBaseUnits,
            String operationMode,
            String signingMode,
            int maxSlippageBps,
            String reason,
            RealOrderState state,
            String failureCode,
            Instant quoteAt,
            Long expectedOut,
            Long minOut,
            boolean requoted,
            Instant signingDeadline,
            Instant signingClaimedAt,
            Instant signingRequestedAt,
            String messageDigest,
            String unsignedMessage,
            String signedTransaction,
            String txSignature,
            Long lastValidBlockHeight,
            Instant sentAt,
            Long outBaseUnits,
            Long feeLamports,
            String feePayer,
            Instant createdAt,
            Instant updatedAt,
            // Review of the U4 (Minor 5 and 6): the wallet of the RESERVE, fixed at creation, and the rent read from
            // the network; null on rows written before the review.
            UUID botWalletId,
            Long rentLamports) {

        public boolean liquidation() {
            return "USER_LIQUIDATION".equals(origin);
        }

        public boolean signedMode() {
            return "SIGNED".equals(signingMode);
        }

        public long input() {
            return side == RealSide.BUY ? inputLamports : inputBaseUnits;
        }
    }

    /** A new PROPOSED order. */
    public record NewOrder(
            UUID realOrderId,
            UUID tenantId,
            UUID userId,
            UUID botId,
            UUID liquidationId,
            UUID decisionRequestId,
            String origin,
            String idempotencyKey,
            RealSide side,
            String mint,
            Long inputLamports,
            Long inputBaseUnits,
            String operationMode,
            String signingMode,
            int maxSlippageBps,
            String reason,
            Instant now,
            UUID botWalletId) {}

    public void insertOrder(NewOrder order) {
        jdbc.sql("""
                        insert into real_order (real_order_id, tenant_id, user_id, bot_id, liquidation_id,
                          decision_request_id, origin, idempotency_key, side, mint, input_lamports, input_base_units,
                          operation_mode, signing_mode, max_slippage_bps, reason, state, created_at, updated_at,
                          bot_wallet_id)
                        values (:id, :tenant, :user, :bot, :liquidation, :decision, :origin, :key, :side, :mint,
                          :lamports, :units, :mode, :signing, :slippage, :reason, 'PROPOSED', :now, :now, :wallet)""")
                .param("wallet", order.botWalletId())
                .param("id", order.realOrderId())
                .param("tenant", order.tenantId())
                .param("user", order.userId())
                .param("bot", order.botId())
                .param("liquidation", order.liquidationId())
                .param("decision", order.decisionRequestId())
                .param("origin", order.origin())
                .param("key", order.idempotencyKey())
                .param("side", order.side().name())
                .param("mint", order.mint())
                .param("lamports", order.inputLamports())
                .param("units", order.inputBaseUnits())
                .param("mode", order.operationMode())
                .param("signing", order.signingMode())
                .param("slippage", order.maxSlippageBps())
                .param("reason", order.reason())
                .param("now", Timestamp.from(order.now()))
                .update();
    }

    public boolean hasOrderInProgress(UUID tenantId, UUID botId) {
        return jdbc.sql("""
                        select exists (select 1 from real_order where tenant_id = :tenant and bot_id = :bot
                          and state in ('PROPOSED', 'QUOTED', 'SIGNED', 'SENT', 'AMBIGUOUS'))""")
                .param("tenant", tenantId)
                .param("bot", botId)
                .query(Boolean.class)
                .single();
    }

    public boolean hasAmbiguous(UUID tenantId, UUID userId) {
        return jdbc.sql("""
                        select exists (select 1 from real_order where tenant_id = :tenant and user_id = :user
                          and state = 'AMBIGUOUS')""")
                .param("tenant", tenantId)
                .param("user", userId)
                .query(Boolean.class)
                .single();
    }

    /** Orders of the person not yet final nor AMBIGUOUS (the liquidation waits for them). */
    public boolean hasOrderInFlight(UUID tenantId, UUID userId) {
        return jdbc.sql("""
                        select exists (select 1 from real_order where tenant_id = :tenant and user_id = :user
                          and state in ('PROPOSED', 'QUOTED', 'SIGNED', 'SENT'))""")
                .param("tenant", tenantId)
                .param("user", userId)
                .query(Boolean.class)
                .single();
    }

    public Optional<OrderRow> find(UUID realOrderId) {
        return jdbc.sql("select " + ORDER_COLUMNS + " from real_order where real_order_id = :id")
                .param("id", realOrderId)
                .query(JdbcRealExecutionStore::order)
                .optional();
    }

    /** An order of THIS person only (BR10.2): another person's order is simply not found. */
    public Optional<OrderRow> findForOwner(UUID tenantId, UUID userId, UUID realOrderId) {
        return jdbc.sql("select " + ORDER_COLUMNS + " from real_order where real_order_id = :id"
                        + " and tenant_id = :tenant and user_id = :user")
                .param("id", realOrderId)
                .param("tenant", tenantId)
                .param("user", userId)
                .query(JdbcRealExecutionStore::order)
                .optional();
    }

    /**
     * Takes the lease of up to {@code limit} orders in one of {@code states} whose lease is free or expired, oldest
     * update first, in ONE statement with {@code SKIP LOCKED}: two instances never take the same order.
     *
     * @param extraCondition an additional condition on the order (a fixed fragment of this class, never input)
     */
    public List<UUID> claim(
            Collection<RealOrderState> states, String extraCondition, String owner, Duration lease, int limit) {
        String inStates = states.stream().map(s -> "'" + s.name() + "'").collect(Collectors.joining(", "));
        return jdbc.sql("update real_order set lease_owner = :owner, lease_until = now() + cast(:lease as interval)"
                        + " where real_order_id in (select real_order_id from real_order where state in (" + inStates
                        + ")" + (extraCondition.isBlank() ? "" : " and " + extraCondition)
                        + " and (lease_owner is null or lease_until < now())"
                        + " order by updated_at limit :limit for update skip locked)"
                        + " returning real_order_id")
                .param("owner", owner)
                .param("lease", lease.toMillis() + " milliseconds")
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    /**
     * The compare-and-set of every transition (U4-NFR4.2): sets {@code columns} only when the order is still in
     * {@code expected} and, when {@code owner} is present, still held by it; renews the lease of the holder. Zero rows
     * means another writer won: the caller does nothing more.
     */
    public boolean update(
            UUID realOrderId,
            RealOrderState expected,
            Optional<String> owner,
            Map<String, Object> columns,
            Duration lease,
            Instant now) {
        Map<String, Object> sets = new LinkedHashMap<>(columns);
        sets.put("updated_at", Timestamp.from(now));
        StringBuilder sql = new StringBuilder("update real_order set ");
        List<String> assignments = new ArrayList<>();
        sets.keySet().forEach(column -> assignments.add(column + " = :" + column));
        sql.append(String.join(", ", assignments));
        Object target = sets.get("state");
        boolean ends =
                target != null && RealOrderState.valueOf(String.valueOf(target)).isFinal();
        if (ends) {
            sql.append(", lease_owner = null, lease_until = null");
        } else if (owner.isPresent()) {
            sql.append(", lease_until = now() + cast(:lease as interval)");
        }
        sql.append(" where real_order_id = :realOrderId and state = :expectedState");
        owner.ifPresent(o -> sql.append(" and lease_owner = :leaseOwner"));
        var statement =
                jdbc.sql(sql.toString()).param("realOrderId", realOrderId).param("expectedState", expected.name());
        for (Map.Entry<String, Object> set : sets.entrySet()) {
            statement = statement.param(set.getKey(), set.getValue());
        }
        if (owner.isPresent()) {
            statement = statement.param("leaseOwner", owner.get());
            if (!ends) {
                statement = statement.param("lease", lease.toMillis() + " milliseconds");
            }
        }
        return statement.update() == 1;
    }

    /**
     * BR4.2, S25: the claim of the signing session, a compare-and-set on QUOTED, unclaimed and within the deadline; the
     * transaction signed by the main wallet is recorded with it. Exactly one of this claim and the cancellation of the
     * deadline job wins.
     */
    public boolean claimSession(UUID realOrderId, Instant now, String signedByMain) {
        return jdbc.sql("""
                        update real_order set signing_claimed_at = :now, signed_transaction = :tx, updated_at = :now
                        where real_order_id = :id and state = 'QUOTED' and signing_mode = 'SIGNED'
                          and signing_claimed_at is null and signing_deadline > :now""")
                        .param("id", realOrderId)
                        .param("now", Timestamp.from(now))
                        .param("tx", signedByMain)
                        .update()
                == 1;
    }

    /**
     * BR4.2, S8: the unclaimed sessions past their deadline become CANCELLED SIGNING_DEADLINE_EXPIRED, in one statement
     * that never touches a claimed one. Returns the cancelled orders.
     */
    public List<OrderRow> cancelExpiredSessions(Instant now, int limit) {
        return jdbc.sql("update real_order set state = 'CANCELLED', failure_code = 'SIGNING_DEADLINE_EXPIRED',"
                        + " updated_at = :now, lease_owner = null, lease_until = null where real_order_id in ("
                        + "select real_order_id from real_order where state = 'QUOTED' and signing_mode = 'SIGNED'"
                        + " and signing_claimed_at is null and signing_deadline <= :now limit :limit"
                        + " for update skip locked) and state = 'QUOTED' and signing_claimed_at is null"
                        + " returning " + ORDER_COLUMNS)
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query(JdbcRealExecutionStore::order)
                .list();
    }

    /** Gives the lease back when the step leaves the order as it was (the next round may take it at once). */
    public void releaseLease(UUID realOrderId, String owner) {
        jdbc.sql("update real_order set lease_owner = null, lease_until = null"
                        + " where real_order_id = :id and lease_owner = :owner")
                .param("id", realOrderId)
                .param("owner", owner)
                .update();
    }

    /**
     * F10, BR7.1: cancels the orders of the scope in {@code states} with {@code code}, in one statement, without
     * looking at the lease (a cancellation never waits for the driver). Returns the cancelled orders.
     */
    public List<OrderRow> cancel(
            CancelScope scope, Collection<RealOrderState> states, RealFailureCode code, Instant now) {
        String inStates = states.stream().map(s -> "'" + s.name() + "'").collect(Collectors.joining(", "));
        String where = "state in (" + inStates + ")";
        var statement = jdbc.sql("update real_order set state = 'CANCELLED', failure_code = :code, updated_at = :now,"
                + " lease_owner = null, lease_until = null where " + where
                + (scope instanceof CancelScope.User ? " and tenant_id = :tenant and user_id = :user" : "")
                + " returning " + ORDER_COLUMNS);
        statement = statement.param("code", code.name()).param("now", Timestamp.from(now));
        if (scope instanceof CancelScope.User user) {
            statement = statement.param("tenant", user.tenantId()).param("user", user.userId());
        }
        return statement.query(JdbcRealExecutionStore::order).list();
    }

    /** The orders of the person, newest first, for the routes R3 (cursor by creation and id). */
    public List<OrderRow> listForOwner(
            UUID tenantId,
            UUID userId,
            Optional<UUID> botId,
            Optional<RealOrderState> state,
            Optional<Instant> beforeCreatedAt,
            Optional<UUID> beforeId,
            int limit) {
        StringBuilder sql = new StringBuilder(
                "select " + ORDER_COLUMNS + " from real_order where tenant_id = :tenant and user_id = :user");
        botId.ifPresent(b -> sql.append(" and bot_id = :bot"));
        state.ifPresent(s -> sql.append(" and state = :state"));
        if (beforeCreatedAt.isPresent() && beforeId.isPresent()) {
            sql.append(" and (created_at, real_order_id) < (:before, :beforeId)");
        }
        sql.append(" order by created_at desc, real_order_id desc limit :limit");
        var statement = jdbc.sql(sql.toString())
                .param("tenant", tenantId)
                .param("user", userId)
                .param("limit", limit);
        if (botId.isPresent()) {
            statement = statement.param("bot", botId.get());
        }
        if (state.isPresent()) {
            statement = statement.param("state", state.get().name());
        }
        if (beforeCreatedAt.isPresent() && beforeId.isPresent()) {
            statement = statement
                    .param("before", Timestamp.from(beforeCreatedAt.get()))
                    .param("beforeId", beforeId.get());
        }
        return statement.query(JdbcRealExecutionStore::order).list();
    }

    public long countForOwner(UUID tenantId, UUID userId) {
        return jdbc.sql("select count(*) from real_order where tenant_id = :tenant and user_id = :user")
                .param("tenant", tenantId)
                .param("user", userId)
                .query(Long.class)
                .single();
    }

    /** BR6.2: the orders of the person that reached the network, oldest first. */
    public List<OrderRow> settled(UUID tenantId, UUID userId) {
        return jdbc.sql("select " + ORDER_COLUMNS + " from real_order where tenant_id = :tenant and user_id = :user"
                        + " and tx_signature is not null and state in ('CONFIRMED', 'FAILED') order by updated_at,"
                        + " real_order_id")
                .param("tenant", tenantId)
                .param("user", userId)
                .query(JdbcRealExecutionStore::order)
                .list();
    }

    /** Orders of {@code states} with an extra fixed condition (the jobs), oldest update first. */
    public List<OrderRow> inStates(Collection<RealOrderState> states, String extraCondition, int limit) {
        String inStates = states.stream().map(s -> "'" + s.name() + "'").collect(Collectors.joining(", "));
        return jdbc.sql("select " + ORDER_COLUMNS + " from real_order where state in (" + inStates + ")"
                        + (extraCondition.isBlank() ? "" : " and " + extraCondition)
                        + " order by updated_at limit :limit")
                .param("limit", limit)
                .query(JdbcRealExecutionStore::order)
                .list();
    }

    /** The changes of the orders of the person after {@code since}, for the live channel (BR10.1). */
    public List<OrderRow> changedSince(UUID tenantId, UUID userId, Instant since, int limit) {
        return jdbc.sql("select " + ORDER_COLUMNS + " from real_order where tenant_id = :tenant and user_id = :user"
                        + " and updated_at > :since order by updated_at limit :limit")
                .param("tenant", tenantId)
                .param("user", userId)
                .param("since", Timestamp.from(since))
                .param("limit", limit)
                .query(JdbcRealExecutionStore::order)
                .list();
    }

    /** The mark of the live channel of the person: the newest update of their orders, in milliseconds (or 0). */
    public long markFor(UUID tenantId, UUID userId) {
        return jdbc.sql("select coalesce(max(updated_at), 'epoch'::timestamptz) from real_order"
                        + " where tenant_id = :tenant and user_id = :user")
                .param("tenant", tenantId)
                .param("user", userId)
                .query(Timestamp.class)
                .single()
                .toInstant()
                .toEpochMilli();
    }

    // ----- liquidations (real_liquidation) -----

    /** One row of real_liquidation. */
    public record LiquidationRow(UUID liquidationId, UUID tenantId, UUID userId, String state, UUID withdrawalId) {}

    public void insertLiquidation(UUID liquidationId, UUID tenantId, UUID userId, Instant now) {
        jdbc.sql("insert into real_liquidation (liquidation_id, tenant_id, user_id, state, created_at, updated_at)"
                        + " values (:id, :tenant, :user, 'SELLING', :now, :now)")
                .param("id", liquidationId)
                .param("tenant", tenantId)
                .param("user", userId)
                .param("now", Timestamp.from(now))
                .update();
    }

    /** The liquidations the job drives (SELLING and WITHDRAWING), oldest first. */
    public List<LiquidationRow> liquidationsInProgress(int limit) {
        return jdbc.sql("select liquidation_id, tenant_id, user_id, state, withdrawal_id from real_liquidation"
                        + " where state in ('SELLING', 'WITHDRAWING') order by created_at limit :limit")
                .param("limit", limit)
                .query((rs, row) -> new LiquidationRow(
                        rs.getObject("liquidation_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("state"),
                        rs.getObject("withdrawal_id", UUID.class)))
                .list();
    }

    /** The compare-and-set of a liquidation (BR8.2): SELLING to WITHDRAWING, WITHDRAWING to DONE or PARTIAL. */
    public boolean moveLiquidation(
            UUID liquidationId, String from, String to, UUID withdrawalId, String remainingJson, Instant now) {
        return jdbc.sql("""
                        update real_liquidation set state = :to, withdrawal_id = coalesce(:withdrawal, withdrawal_id),
                          remaining = cast(:remaining as jsonb), updated_at = :now
                        where liquidation_id = :id and state = :from""")
                        .param("id", liquidationId)
                        .param("from", from)
                        .param("to", to)
                        .param("withdrawal", withdrawalId)
                        .param("remaining", remainingJson)
                        .param("now", Timestamp.from(now))
                        .update()
                == 1;
    }

    /** The sales of a liquidation, oldest first. */
    public List<OrderRow> salesOf(UUID liquidationId) {
        return jdbc.sql("select " + ORDER_COLUMNS + " from real_order where liquidation_id = :id order by created_at")
                .param("id", liquidationId)
                .query(JdbcRealExecutionStore::order)
                .list();
    }

    /** The mints of the AMBIGUOUS orders of the person (BR8.2: they stay in the remaining, "em conferência"). */
    public List<String> ambiguousMints(UUID tenantId, UUID userId) {
        return jdbc.sql("select distinct mint from real_order where tenant_id = :tenant and user_id = :user"
                        + " and state = 'AMBIGUOUS'")
                .param("tenant", tenantId)
                .param("user", userId)
                .query(String.class)
                .list();
    }

    /** A sale of the liquidation refused by the barrier: recorded CANCELLED so it is never tried again. */
    public void insertRefusedSale(NewOrder order, String failureCode) {
        jdbc.sql("""
                        insert into real_order (real_order_id, tenant_id, user_id, bot_id, liquidation_id,
                          decision_request_id, origin, idempotency_key, side, mint, input_lamports, input_base_units,
                          operation_mode, signing_mode, max_slippage_bps, reason, state, failure_code, created_at,
                          updated_at)
                        values (:id, :tenant, :user, null, :liquidation, null, 'USER_LIQUIDATION', :key, 'SELL', :mint,
                          null, :units, :mode, :signing, :slippage, :reason, 'CANCELLED', :code, :now, :now)""")
                .param("id", order.realOrderId())
                .param("tenant", order.tenantId())
                .param("user", order.userId())
                .param("liquidation", order.liquidationId())
                .param("key", order.idempotencyKey())
                .param("mint", order.mint())
                .param("units", order.inputBaseUnits())
                .param("mode", order.operationMode())
                .param("signing", order.signingMode())
                .param("slippage", order.maxSlippageBps())
                .param("reason", order.reason())
                .param("code", failureCode)
                .param("now", Timestamp.from(order.now()))
                .update();
    }

    public boolean liquidationActive(UUID tenantId, UUID userId) {
        return jdbc.sql("""
                        select exists (select 1 from real_liquidation where tenant_id = :tenant and user_id = :user
                          and state in ('SELLING', 'WITHDRAWING'))""")
                .param("tenant", tenantId)
                .param("user", userId)
                .query(Boolean.class)
                .single();
    }

    // ----- sends (real_order_attempt) -----

    public UUID insertAttempt(UUID realOrderId, UUID tenantId, Instant now) {
        UUID attemptId = UUID.randomUUID();
        jdbc.sql("insert into real_order_attempt (attempt_id, real_order_id, tenant_id, sent_at)"
                        + " values (:id, :order, :tenant, :at)")
                .param("id", attemptId)
                .param("order", realOrderId)
                .param("tenant", tenantId)
                .param("at", Timestamp.from(now))
                .update();
        return attemptId;
    }

    public void resolveAttempt(UUID attemptId, String outcome, Instant now) {
        jdbc.sql("update real_order_attempt set outcome = :outcome, resolved_at = :at"
                        + " where attempt_id = :id and outcome = 'PENDING'")
                .param("id", attemptId)
                .param("outcome", outcome)
                .param("at", Timestamp.from(now))
                .update();
    }

    /** Resolves the sends still PENDING of an order (the answer was lost) with {@code outcome}. */
    public void resolvePendingAttempts(UUID realOrderId, String outcome, Instant now) {
        jdbc.sql("update real_order_attempt set outcome = :outcome, resolved_at = :at"
                        + " where real_order_id = :order and outcome = 'PENDING'")
                .param("order", realOrderId)
                .param("outcome", outcome)
                .param("at", Timestamp.from(now))
                .update();
    }

    // ----- helpers -----

    static OrderRow order(ResultSet rs, int row) throws SQLException {
        return new OrderRow(
                rs.getObject("real_order_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("bot_id", UUID.class),
                rs.getObject("liquidation_id", UUID.class),
                rs.getObject("decision_request_id", UUID.class),
                rs.getString("origin"),
                rs.getString("idempotency_key"),
                RealSide.valueOf(rs.getString("side")),
                rs.getString("mint"),
                nullableLong(rs, "input_lamports"),
                nullableLong(rs, "input_base_units"),
                rs.getString("operation_mode"),
                rs.getString("signing_mode"),
                rs.getInt("max_slippage_bps"),
                rs.getString("reason"),
                RealOrderState.valueOf(rs.getString("state")),
                rs.getString("failure_code"),
                instant(rs, "quote_at"),
                nullableLong(rs, "expected_out"),
                nullableLong(rs, "min_out"),
                rs.getBoolean("requoted"),
                instant(rs, "signing_deadline"),
                instant(rs, "signing_claimed_at"),
                instant(rs, "signing_requested_at"),
                rs.getString("message_digest"),
                rs.getString("unsigned_message"),
                rs.getString("signed_transaction"),
                rs.getString("tx_signature"),
                nullableLong(rs, "last_valid_block_height"),
                instant(rs, "sent_at"),
                nullableLong(rs, "out_base_units"),
                nullableLong(rs, "fee_lamports"),
                rs.getString("fee_payer"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getObject("bot_wallet_id", UUID.class),
                nullableLong(rs, "rent_lamports"));
    }

    static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
