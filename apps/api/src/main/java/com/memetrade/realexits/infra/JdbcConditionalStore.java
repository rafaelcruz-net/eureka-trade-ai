package com.memetrade.realexits.infra;

import com.memetrade.realexits.domain.AdapterKind;
import com.memetrade.realexits.domain.CancelReason;
import com.memetrade.realexits.domain.ConditionalKind;
import com.memetrade.realexits.domain.ConditionalOrder;
import com.memetrade.realexits.domain.ConditionalState;
import com.memetrade.realexits.domain.ConditionalStateMachine;
import com.memetrade.realexits.domain.ExitSide;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JDBC adapter of {@code real_conditional_order} (migration 0022). Every read of a person filters by tenant and person
 * (team.md); the jobs read by state and act on each row with the tenant and the person recorded on it. Every change of
 * state is a compare-and-set on the current state (U7-NFR4.1), checked first by the {@link ConditionalStateMachine}
 * and again by the trigger of the database.
 */
@Repository
public class JdbcConditionalStore {

    /** The columns a change may set besides the state (the trigger of 0022 allows exactly these). */
    private static final Set<String> MUTABLE = Set.of(
            "jupiter_order_id",
            "create_signature",
            "fill_signature",
            "cancel_signature",
            "escrow_account",
            "filled_side",
            "signing_message",
            "signing_deadline",
            "cancel_reason",
            "last_read_at",
            "cancelling_since",
            "filled_at");

    private final JdbcClient jdbc;

    public JdbcConditionalStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A new conditional, always PENDING_SIGNATURE with its signing session (entities.md). */
    public record NewConditional(
            UUID conditionalOrderId,
            UUID tenantId,
            UUID userId,
            UUID botWalletId,
            String positionMint,
            ConditionalKind kind,
            Optional<BigDecimal> stopPriceUsd,
            Optional<BigDecimal> takeProfitPriceUsd,
            long amountBaseUnits,
            int slippageBps,
            AdapterKind adapter,
            String idempotencyKey,
            String signingMessage,
            Instant signingDeadline,
            Instant createdAt) {}

    /**
     * Inserts the conditional; a second conditional that is not final on the same position violates the partial
     * unique index (BR3.1) and throws {@link org.springframework.dao.DuplicateKeyException}.
     */
    public void insert(NewConditional row) {
        jdbc.sql("""
                        insert into real_conditional_order (conditional_order_id, tenant_id, user_id, bot_wallet_id,
                          position_mint, kind, stop_price_usd, take_profit_price_usd, amount_base_units, slippage_bps,
                          state, adapter, idempotency_key, signing_message, signing_deadline, created_at, updated_at)
                        values (:id, :tenant, :user, :wallet, :mint, :kind, :stop, :tp, :amount, :slippage,
                          'PENDING_SIGNATURE', :adapter, :key, :message, :deadline, :at, :at)""")
                .param("id", row.conditionalOrderId())
                .param("tenant", row.tenantId())
                .param("user", row.userId())
                .param("wallet", row.botWalletId())
                .param("mint", row.positionMint())
                .param("kind", row.kind().name())
                .param("stop", row.stopPriceUsd().orElse(null))
                .param("tp", row.takeProfitPriceUsd().orElse(null))
                .param("amount", BigDecimal.valueOf(row.amountBaseUnits()))
                .param("slippage", row.slippageBps())
                .param("adapter", row.adapter().name())
                .param("key", row.idempotencyKey())
                .param("message", row.signingMessage())
                .param("deadline", Timestamp.from(row.signingDeadline()))
                .param("at", Timestamp.from(row.createdAt()))
                .update();
    }

    public Optional<ConditionalOrder> find(UUID conditionalOrderId) {
        return jdbc.sql("select * from real_conditional_order where conditional_order_id = :id")
                .param("id", conditionalOrderId)
                .query(JdbcConditionalStore::row)
                .optional();
    }

    /** A conditional of THIS person; another person's is simply not found (404). */
    public Optional<ConditionalOrder> findForOwner(UUID tenantId, UUID userId, UUID conditionalOrderId) {
        return jdbc.sql("select * from real_conditional_order where conditional_order_id = :id and tenant_id = :tenant"
                        + " and user_id = :user")
                .param("id", conditionalOrderId)
                .param("tenant", tenantId)
                .param("user", userId)
                .query(JdbcConditionalStore::row)
                .optional();
    }

    /** BR4.4: the conditional created with this key by this person. */
    public Optional<ConditionalOrder> findByKey(UUID tenantId, UUID userId, String idempotencyKey) {
        return jdbc.sql("select * from real_conditional_order where tenant_id = :tenant and user_id = :user"
                        + " and idempotency_key = :key")
                .param("tenant", tenantId)
                .param("user", userId)
                .param("key", idempotencyKey)
                .query(JdbcConditionalStore::row)
                .optional();
    }

    /**
     * BR4.3: the conditional that is not final on the position of this person, locked for the rest of the transaction
     * of the caller (the guard runs under the person lock; the row lock orders it with the jobs).
     */
    public Optional<ConditionalOrder> lockNotFinalForPosition(UUID tenantId, UUID userId, String mint) {
        return jdbc
                .sql("""
                        select * from real_conditional_order
                        where tenant_id = :tenant and user_id = :user and position_mint = :mint
                          and state in ('PENDING_SIGNATURE', 'OPEN', 'CANCELLING')
                        for update""")
                .param("tenant", tenantId)
                .param("user", userId)
                .param("mint", mint)
                .query(JdbcConditionalStore::row)
                .list()
                .stream()
                .findFirst();
    }

    /** BR6.9: the person has a conditional that is not final. */
    public boolean hasNotFinal(UUID tenantId, UUID userId) {
        return jdbc.sql("""
                        select exists (select 1 from real_conditional_order where tenant_id = :tenant and user_id = :user
                          and state in ('PENDING_SIGNATURE', 'OPEN', 'CANCELLING'))""")
                .param("tenant", tenantId)
                .param("user", userId)
                .query(Boolean.class)
                .single();
    }

    /** BR5.3b: the conditionals of the Jupiter OPEN or CANCELLING of the bot wallet of this tenant. */
    public List<ConditionalOrder> openAtTheProvider(UUID tenantId, UUID botWalletId) {
        return jdbc.sql("""
                        select * from real_conditional_order where tenant_id = :tenant and bot_wallet_id = :wallet
                          and adapter = 'JUPITER' and state in ('OPEN', 'CANCELLING')
                        order by created_at""")
                .param("tenant", tenantId)
                .param("wallet", botWalletId)
                .query(JdbcConditionalStore::row)
                .list();
    }

    /** The list of the person, newest first, after the cursor (created_at, id), at most {@code limit}. */
    public List<ConditionalOrder> page(UUID tenantId, UUID userId, Optional<Cursor> after, int limit) {
        StringBuilder sql =
                new StringBuilder("select * from real_conditional_order where tenant_id = :tenant and user_id = :user");
        if (after.isPresent()) {
            sql.append(" and (created_at, conditional_order_id) < (:at, :id)");
        }
        sql.append(" order by created_at desc, conditional_order_id desc limit :limit");
        JdbcClient.StatementSpec statement = jdbc.sql(sql.toString())
                .param("tenant", tenantId)
                .param("user", userId)
                .param("limit", limit);
        if (after.isPresent()) {
            statement = statement
                    .param("at", Timestamp.from(after.get().createdAt()))
                    .param("id", after.get().conditionalOrderId());
        }
        return statement.query(JdbcConditionalStore::row).list();
    }

    public long count(UUID tenantId, UUID userId) {
        return jdbc.sql("select count(*) from real_conditional_order where tenant_id = :tenant and user_id = :user")
                .param("tenant", tenantId)
                .param("user", userId)
                .query(Long.class)
                .single();
    }

    /** The position of a page: the creation instant and the id of the last item. */
    public record Cursor(Instant createdAt, UUID conditionalOrderId) {}

    /** The jobs: the conditionals in these states, the least recently changed first, at most {@code limit}. */
    public List<ConditionalOrder> inStates(Collection<ConditionalState> states, int limit) {
        return jdbc.sql("select * from real_conditional_order where state in (:states) order by updated_at,"
                        + " conditional_order_id limit :limit")
                .param("states", states.stream().map(Enum::name).toList())
                .param("limit", limit)
                .query(JdbcConditionalStore::row)
                .list();
    }

    /**
     * The compare-and-set of a change of state (U7-NFR4.1): moves {@code from} to {@code to} with the given columns,
     * stamps {@code updated_at} and, for a final state, {@code final_at}; the message of the signing session is cleared
     * when the conditional leaves PENDING_SIGNATURE.
     *
     * @return false when the conditional is no longer in {@code from} (another worker moved it first)
     */
    public boolean move(
            UUID conditionalOrderId, ConditionalState from, ConditionalState to, Changes changes, Instant now) {
        ConditionalStateMachine.require(from, to);
        Map<String, Object> values = new LinkedHashMap<>(changes.values());
        if (from == ConditionalState.PENDING_SIGNATURE) {
            values.put("signing_message", null);
        }
        return update(conditionalOrderId, from, to, values, false, now);
    }

    /** A change without a change of state (the last successful read, a new cancellation sent); also compare-and-set. */
    public boolean touch(UUID conditionalOrderId, ConditionalState in, Changes changes, Instant now) {
        return update(conditionalOrderId, in, in, changes.values(), changes.countsAttempt(), now);
    }

    private boolean update(
            UUID conditionalOrderId,
            ConditionalState from,
            ConditionalState to,
            Map<String, Object> values,
            boolean countsAttempt,
            Instant now) {
        StringBuilder sql = new StringBuilder("update real_conditional_order set state = :to, updated_at = :now");
        if (to.isFinal()) {
            sql.append(", final_at = :now");
        }
        if (countsAttempt) {
            sql.append(", cancel_attempts = cancel_attempts + 1");
        }
        List<String> columns = new ArrayList<>(values.keySet());
        for (int i = 0; i < columns.size(); i++) {
            if (!MUTABLE.contains(columns.get(i))) {
                throw new IllegalArgumentException("Not a mutable column: " + columns.get(i));
            }
            sql.append(", ").append(columns.get(i)).append(" = :v").append(i);
        }
        sql.append(" where conditional_order_id = :id and state = :from");
        JdbcClient.StatementSpec statement = jdbc.sql(sql.toString())
                .param("id", conditionalOrderId)
                .param("from", from.name())
                .param("to", to.name())
                .param("now", Timestamp.from(now));
        for (int i = 0; i < columns.size(); i++) {
            statement = statement.param("v" + i, sqlValue(values.get(columns.get(i))));
        }
        return statement.update() == 1;
    }

    private static Object sqlValue(Object value) {
        if (value instanceof Instant instant) {
            return Timestamp.from(instant);
        }
        if (value instanceof Enum<?> name) {
            return name.name();
        }
        return value;
    }

    /** The columns of one change; only the ones of {@link #MUTABLE}. */
    public static final class Changes {

        private final Map<String, Object> values = new LinkedHashMap<>();
        private boolean countsAttempt;

        public static Changes none() {
            return new Changes();
        }

        public Changes set(String column, Object value) {
            values.put(Objects.requireNonNull(column, "column"), value);
            return this;
        }

        /** A new cancellation was sent: {@code cancel_attempts + 1}. */
        public Changes countAttempt() {
            countsAttempt = true;
            return this;
        }

        Map<String, Object> values() {
            return values;
        }

        boolean countsAttempt() {
            return countsAttempt;
        }
    }

    // ----- the live channel (RealExitChanges) -----

    /** The newest change of a conditional of the person, in milliseconds; 0 without any. */
    public long changeMark(UUID tenantId, UUID userId) {
        return jdbc.sql("select coalesce(max(updated_at), 'epoch'::timestamptz) from real_conditional_order"
                        + " where tenant_id = :tenant and user_id = :user")
                .param("tenant", tenantId)
                .param("user", userId)
                .query(Timestamp.class)
                .single()
                .toInstant()
                .toEpochMilli();
    }

    public List<ConditionalOrder> changedSince(UUID tenantId, UUID userId, Instant since, int limit) {
        return jdbc.sql("""
                        select * from real_conditional_order where tenant_id = :tenant and user_id = :user
                          and updated_at > :since
                        order by updated_at, conditional_order_id limit :limit""")
                .param("tenant", tenantId)
                .param("user", userId)
                .param("since", Timestamp.from(since))
                .param("limit", limit)
                .query(JdbcConditionalStore::row)
                .list();
    }

    // ----- the gauges (U7-NFR5.1, U7-NFR5.3, U7-NFR5.6b) -----

    /** The conditionals by state (every state, zero included). */
    public Map<ConditionalState, Long> countsByState() {
        Map<ConditionalState, Long> counts = new LinkedHashMap<>();
        for (ConditionalState state : ConditionalState.values()) {
            counts.put(state, 0L);
        }
        jdbc.sql("select state, count(*) as total from real_conditional_order group by state")
                .query(rs -> {
                    counts.put(ConditionalState.valueOf(rs.getString("state")), rs.getLong("total"));
                });
        return counts;
    }

    /** The start of the oldest cancellation still in CANCELLING. */
    public Optional<Instant> oldestCancellingSince() {
        return jdbc.sql("select min(cancelling_since) from real_conditional_order where state = 'CANCELLING'")
                .query(Timestamp.class)
                .optional()
                .map(Timestamp::toInstant);
    }

    /** The oldest successful read among the OPEN conditionals (the creation when never read). */
    public Optional<Instant> oldestOpenRead() {
        return jdbc.sql("select min(coalesce(last_read_at, created_at)) from real_conditional_order"
                        + " where state = 'OPEN'")
                .query(Timestamp.class)
                .optional()
                .map(Timestamp::toInstant);
    }

    static ConditionalOrder row(ResultSet rs, int rowNumber) throws SQLException {
        return new ConditionalOrder(
                rs.getObject("conditional_order_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("bot_wallet_id", UUID.class),
                rs.getString("position_mint"),
                ConditionalKind.valueOf(rs.getString("kind")),
                Optional.ofNullable(rs.getBigDecimal("stop_price_usd")).map(BigDecimal::stripTrailingZeros),
                Optional.ofNullable(rs.getBigDecimal("take_profit_price_usd")).map(BigDecimal::stripTrailingZeros),
                rs.getBigDecimal("amount_base_units").longValueExact(),
                rs.getInt("slippage_bps"),
                ConditionalState.valueOf(rs.getString("state")),
                AdapterKind.valueOf(rs.getString("adapter")),
                Optional.ofNullable(rs.getString("jupiter_order_id")),
                Optional.ofNullable(rs.getString("create_signature")),
                Optional.ofNullable(rs.getString("fill_signature")),
                Optional.ofNullable(rs.getString("cancel_signature")),
                Optional.ofNullable(rs.getString("escrow_account")),
                Optional.ofNullable(rs.getString("filled_side")).map(ExitSide::valueOf),
                rs.getString("idempotency_key"),
                Optional.ofNullable(rs.getString("signing_message")),
                instant(rs, "signing_deadline"),
                Optional.ofNullable(rs.getString("cancel_reason")).map(CancelReason::valueOf),
                rs.getInt("cancel_attempts"),
                instant(rs, "last_read_at"),
                instant(rs, "cancelling_since"),
                instant(rs, "filled_at"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static Optional<Instant> instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? Optional.empty() : Optional.of(value.toInstant());
    }
}
