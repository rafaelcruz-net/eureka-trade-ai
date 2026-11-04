package com.memetrade.ledger.infra;

import com.memetrade.identity.api.Principal;
import com.memetrade.ledger.api.PaperEnums.AccountStatus;
import com.memetrade.ledger.api.PaperEnums.EntryType;
import com.memetrade.ledger.api.PaperEnums.PositionStatus;
import com.memetrade.ledger.api.PaperExecution.FillDetails;
import com.memetrade.ledger.domain.FillQuote;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence of the {@code ledger} module (logical-components.md): parameterised SQL only, {@code ON CONFLICT} for
 * the idempotent order, {@code FOR UPDATE} in the lock order of BR2.8 (account, positions by chain and mint, daily
 * summary; the peak belongs to {@code pnl}). Every write is PAPER. A database failure propagates as a technical
 * {@link org.springframework.dao.DataAccessException}; the cycle of U5 rolls back.
 */
@Repository
public class JdbcLedgerRepository {

    private final JdbcClient jdbc;

    public JdbcLedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A new order, written once with its final status (finding of the design review). */
    public record NewOrder(
            UUID orderId,
            UUID tenantId,
            UUID ownerUserId,
            UUID botId,
            UUID decisionId,
            String idempotencyKey,
            String contentDigest,
            String kind,
            String side,
            String chain,
            String tokenMint,
            BigDecimal requestedQuantity,
            long limitsVersion,
            String status,
            String notFilledReason,
            Instant createdAt) {}

    public record StoredOrder(
            UUID orderId,
            UUID tenantId,
            UUID ownerUserId,
            UUID botId,
            String idempotencyKey,
            String contentDigest,
            String kind,
            String side,
            String chain,
            String tokenMint,
            BigDecimal requestedQuantity,
            long limitsVersion,
            String status,
            Optional<String> notFilledReason,
            Instant createdAt) {}

    public record AccountRow(UUID botId, UUID tenantId, UUID ownerUserId, AccountStatus status, Instant createdAt) {}

    public record PositionRow(
            UUID positionId,
            UUID tenantId,
            UUID ownerUserId,
            UUID botId,
            String chain,
            String tokenMint,
            int tokenDecimals,
            BigDecimal quantity,
            BigDecimal costBasis,
            PositionStatus status,
            Instant updatedAt,
            long version) {}

    /** An entry as the compensation reads it. */
    public record EntryRow(
            UUID entryId,
            UUID tenantId,
            UUID ownerUserId,
            UUID botId,
            EntryType type,
            BigDecimal amount,
            String referenceId) {}

    // ---------------------------------------------------------------- timeouts

    /**
     * {@code SET LOCAL} of the lock and statement timeouts (NFR10.8): valid until the end of the current transaction,
     * which for {@code execute} is the cycle of U5 (documented for its design).
     */
    public void setLocalTimeouts(Duration lockTimeout, Optional<Duration> statementTimeout) {
        jdbc.sql("select set_config('lock_timeout', :v, true)")
                .param("v", lockTimeout.toMillis() + "ms")
                .query(String.class)
                .single();
        statementTimeout.ifPresent(timeout -> jdbc.sql("select set_config('statement_timeout', :v, true)")
                .param("v", timeout.toMillis() + "ms")
                .query(String.class)
                .single());
    }

    // ---------------------------------------------------------------- account

    public Optional<AccountRow> findAccount(UUID botId) {
        return jdbc.sql("select bot_id, tenant_id, owner_user_id, status, created_at from paper_account"
                        + " where bot_id = :bot and mode = 'PAPER'")
                .param("bot", botId)
                .query(JdbcLedgerRepository::account)
                .optional();
    }

    /** First lock of BR2.8. */
    public Optional<AccountRow> lockAccount(UUID botId) {
        return locking(() -> jdbc.sql("select bot_id, tenant_id, owner_user_id, status, created_at from paper_account"
                        + " where bot_id = :bot and mode = 'PAPER' for update")
                .param("bot", botId)
                .query(JdbcLedgerRepository::account)
                .optional());
    }

    /**
     * NFR10.8: a lock not obtained within {@code lock_timeout} (SQLSTATE 55P03) becomes a
     * {@link CannotAcquireLockException}, a technical failure the cycle rolls back and the jobs recognise to skip the
     * account in their round.
     */
    static <T> T locking(java.util.function.Supplier<T> statement) {
        try {
            return statement.get();
        } catch (org.springframework.jdbc.UncategorizedSQLException failure) {
            if (failure.getSQLException() != null
                    && LOCK_NOT_AVAILABLE.equals(failure.getSQLException().getSQLState())) {
                throw new CannotAcquireLockException("The lock was not obtained within lock_timeout", failure);
            }
            throw failure;
        }
    }

    static final String LOCK_NOT_AVAILABLE = "55P03";

    public void insertAccount(Principal owner, UUID botId, Instant at) {
        jdbc.sql("""
                        insert into paper_account (bot_id, tenant_id, owner_user_id, mode, status, created_at)
                        values (:bot, :tenant, :user, 'PAPER', 'OPEN', :at)
                        """)
                .param("bot", botId)
                .param("tenant", owner.tenantId())
                .param("user", owner.userId())
                .param("at", Timestamp.from(at))
                .update();
    }

    public void updateAccountStatus(UUID botId, AccountStatus status) {
        jdbc.sql("update paper_account set status = :s where bot_id = :bot")
                .param("s", status.name())
                .param("bot", botId)
                .update();
    }

    // ---------------------------------------------------------------- orders and fills

    /**
     * BR3.1: {@code INSERT ... ON CONFLICT DO NOTHING}; a collision never aborts the transaction of U5 and a
     * concurrent insert of the same key waits for the first transaction.
     *
     * @return true when this call inserted the order
     */
    public boolean insertOrderIfAbsent(NewOrder order) {
        return jdbc.sql("""
                        insert into paper_order (order_id, tenant_id, owner_user_id, bot_id, mode, decision_id,
                          idempotency_key, content_digest, kind, side, chain, token_mint, requested_quantity,
                          limits_version, status, not_filled_reason, created_at)
                        values (:order, :tenant, :user, :bot, 'PAPER', :decision, :key, :digest, :kind, :side, :chain,
                          :mint, :quantity, :limits, :status, :reason, :at)
                        on conflict (tenant_id, mode, idempotency_key) do nothing
                        """)
                        .param("order", order.orderId())
                        .param("tenant", order.tenantId())
                        .param("user", order.ownerUserId())
                        .param("bot", order.botId())
                        .param("decision", order.decisionId())
                        .param("key", order.idempotencyKey())
                        .param("digest", order.contentDigest())
                        .param("kind", order.kind())
                        .param("side", order.side())
                        .param("chain", order.chain())
                        .param("mint", order.tokenMint())
                        .param("quantity", order.requestedQuantity())
                        .param("limits", order.limitsVersion())
                        .param("status", order.status())
                        .param("reason", order.notFilledReason())
                        .param("at", Timestamp.from(order.createdAt()))
                        .update()
                == 1;
    }

    public Optional<StoredOrder> findOrder(UUID tenantId, String idempotencyKey) {
        return jdbc.sql(ORDER_COLUMNS + " where tenant_id = :tenant and mode = 'PAPER' and idempotency_key = :key")
                .param("tenant", tenantId)
                .param("key", idempotencyKey)
                .query(JdbcLedgerRepository::order)
                .optional();
    }

    /**
     * The CLOSE order of a pending position, by equality only (R-05 of the code review): the bot, chain and mint of the
     * position (unique per position) and the exact derived-key suffix {@code :<positionId>} of the key
     * {@code <closing key>:<positionId>}. The closing key itself is not stored (only its digest, in the common
     * idempotency mechanism), so the job and a repeated closing cannot rebuild the whole derived key; no {@code LIKE}
     * is ever applied to text supplied by the caller.
     */
    public Optional<StoredOrder> pendingCloseOrder(PositionRow position) {
        return jdbc.sql(ORDER_COLUMNS + " where bot_id = :bot and mode = 'PAPER' and kind = 'CLOSE'"
                        + " and status = 'CLOSE_PENDING' and chain = :chain and token_mint = :mint"
                        + " and right(idempotency_key, :suffixLength) = :suffix")
                .param("bot", position.botId())
                .param("chain", position.chain())
                .param("mint", position.tokenMint())
                .param("suffixLength", CLOSE_KEY_SUFFIX_LENGTH)
                .param("suffix", ":" + position.positionId())
                .query(JdbcLedgerRepository::order)
                .optional();
    }

    /** A colon and a canonical UUID: the part the derived key of a CLOSE order adds to the closing key. */
    static final int CLOSE_KEY_SUFFIX_LENGTH = 1 + 36;

    /** The only status change of an order: CLOSE_PENDING to FILLED (trigger of the migration). */
    public void markCloseOrderFilled(UUID orderId) {
        int rows = jdbc.sql("update paper_order set status = 'FILLED' where order_id = :o and status = 'CLOSE_PENDING'")
                .param("o", orderId)
                .update();
        if (rows != 1) {
            throw new IllegalStateException("The CLOSE order " + orderId + " is no longer CLOSE_PENDING");
        }
    }

    public FillDetails insertFill(
            StoredOrder order, FillQuote.Computed quote, Optional<BigDecimal> realized, String policy, Instant at) {
        UUID fillId = UUID.randomUUID();
        jdbc.sql("""
                        insert into fill (fill_id, order_id, tenant_id, owner_user_id, bot_id, mode, chain, token_mint,
                          filled_quantity, reference_price, quoted_price, fill_price, spread_pct, impact_pct,
                          slippage_pct, liquidity_consumed_pct, liquidity_used, gross_amount, fees, realized,
                          market_source, market_observed_at, simulation_policy_version, filled_at)
                        values (:fill, :order, :tenant, :user, :bot, 'PAPER', :chain, :mint, :quantity, :reference,
                          :quoted, :price, :spread, :impact, :slippage, :consumed, :liquidity, :gross, :fees,
                          :realized, :source, :observed, :policy, :at)
                        """)
                .param("fill", fillId)
                .param("order", order.orderId())
                .param("tenant", order.tenantId())
                .param("user", order.ownerUserId())
                .param("bot", order.botId())
                .param("chain", order.chain())
                .param("mint", order.tokenMint())
                .param("quantity", quote.filledQuantity())
                .param("reference", quote.referencePrice())
                .param("quoted", quote.quotedPrice())
                .param("price", quote.fillPrice())
                .param("spread", quote.spreadPct())
                .param("impact", quote.impactPct())
                .param("slippage", quote.slippagePct())
                .param("consumed", quote.consumedPct())
                .param("liquidity", quote.liquidity().setScale(6, java.math.RoundingMode.HALF_EVEN))
                .param("gross", quote.grossAmount())
                .param("fees", quote.fee())
                .param("realized", realized.orElse(null))
                .param("source", quote.marketSource())
                .param("observed", Timestamp.from(quote.marketObservedAt()))
                .param("policy", policy)
                .param("at", Timestamp.from(at))
                .update();
        return fill(order.orderId()).orElseThrow();
    }

    public Optional<FillDetails> fill(UUID orderId) {
        return jdbc.sql(FILL_COLUMNS + " from fill f where f.order_id = :order")
                .param("order", orderId)
                .query((rs, row) -> fillDetails(rs))
                .optional();
    }

    // ---------------------------------------------------------------- entries

    public UUID insertEntry(
            Principal owner,
            UUID botId,
            EntryType type,
            BigDecimal amount,
            String referenceId,
            UUID compensatesEntryId,
            Instant at) {
        return insertEntry(owner.tenantId(), owner.userId(), botId, type, amount, referenceId, compensatesEntryId, at);
    }

    public UUID insertEntry(
            UUID tenantId,
            UUID ownerUserId,
            UUID botId,
            EntryType type,
            BigDecimal amount,
            String referenceId,
            UUID compensatesEntryId,
            Instant at) {
        UUID entryId = UUID.randomUUID();
        jdbc.sql("""
                        insert into ledger_entry (entry_id, tenant_id, owner_user_id, bot_id, mode, type, asset, amount,
                          reference_id, compensates_entry_id, created_at)
                        values (:entry, :tenant, :user, :bot, 'PAPER', :type, 'USDC', :amount, :reference,
                          :compensates, :at)
                        """)
                .param("entry", entryId)
                .param("tenant", tenantId)
                .param("user", ownerUserId)
                .param("bot", botId)
                .param("type", type.name())
                .param("amount", amount)
                .param("reference", referenceId)
                .param("compensates", compensatesEntryId)
                .param("at", Timestamp.from(at))
                .update();
        return entryId;
    }

    public Optional<EntryRow> findEntry(UUID entryId) {
        return jdbc.sql("select entry_id, tenant_id, owner_user_id, bot_id, type, amount, reference_id"
                        + " from ledger_entry where entry_id = :e and mode = 'PAPER'")
                .param("e", entryId)
                .query((rs, row) -> new EntryRow(
                        rs.getObject("entry_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("owner_user_id", UUID.class),
                        rs.getObject("bot_id", UUID.class),
                        EntryType.valueOf(rs.getString("type")),
                        rs.getBigDecimal("amount"),
                        rs.getString("reference_id")))
                .optional();
    }

    public boolean isCompensated(UUID entryId) {
        return jdbc.sql("select exists (select 1 from ledger_entry where compensates_entry_id = :e)")
                .param("e", entryId)
                .query(Boolean.class)
                .single();
    }

    /** NFR2.4: the balance is the sum of the entries by the covering index; zero for a bot without entries. */
    public BigDecimal balance(UUID botId) {
        BigDecimal sum = jdbc.sql(
                        "select coalesce(sum(amount), 0) from ledger_entry where bot_id = :bot and mode = 'PAPER'")
                .param("bot", botId)
                .query(BigDecimal.class)
                .single();
        if (sum.signum() < 0) {
            // BR2.5: a negative sum is an inconsistency of the ledger, never a domain result.
            throw new IllegalStateException("The ledger of bot " + botId + " sums to a negative balance");
        }
        return sum.setScale(6);
    }

    // ---------------------------------------------------------------- positions

    /** Second lock of BR2.8: the position of the token, created CLOSED and empty when absent. */
    public PositionRow lockOrCreatePosition(AccountRow account, String chain, String mint, int decimals, Instant at) {
        jdbc.sql("""
                        insert into position (position_id, tenant_id, owner_user_id, bot_id, mode, chain, token_mint,
                          token_decimals, quantity, cost_basis, status, updated_at, version)
                        values (:position, :tenant, :user, :bot, 'PAPER', :chain, :mint, :decimals, 0, 0, 'CLOSED',
                          :at, 0)
                        on conflict (bot_id, chain, token_mint) do nothing
                        """)
                .param("position", UUID.randomUUID())
                .param("tenant", account.tenantId())
                .param("user", account.ownerUserId())
                .param("bot", account.botId())
                .param("chain", chain)
                .param("mint", mint)
                .param("decimals", decimals)
                .param("at", Timestamp.from(at))
                .update();
        return locking(
                () -> jdbc.sql(POSITION_COLUMNS + " where bot_id = :bot and chain = :chain and token_mint = :mint"
                                + " and mode = 'PAPER' for update")
                        .param("bot", account.botId())
                        .param("chain", chain)
                        .param("mint", mint)
                        .query(JdbcLedgerRepository::position)
                        .single());
    }

    /** Every position of the bot, locked in the order (chain, mint) of BR2.8. */
    public List<PositionRow> lockPositions(UUID botId) {
        return locking(() -> jdbc.sql(POSITION_COLUMNS + " where bot_id = :bot and mode = 'PAPER'"
                        + " order by chain, token_mint for update")
                .param("bot", botId)
                .query(JdbcLedgerRepository::position)
                .list());
    }

    public Optional<PositionRow> findPosition(UUID botId, String chain, String mint) {
        return jdbc.sql(POSITION_COLUMNS + " where bot_id = :bot and chain = :chain and token_mint = :mint"
                        + " and mode = 'PAPER'")
                .param("bot", botId)
                .param("chain", chain)
                .param("mint", mint)
                .query(JdbcLedgerRepository::position)
                .optional();
    }

    public List<PositionRow> positions(UUID botId) {
        return jdbc.sql(POSITION_COLUMNS + " where bot_id = :bot and mode = 'PAPER' order by chain, token_mint")
                .param("bot", botId)
                .query(JdbcLedgerRepository::position)
                .list();
    }

    /** F4: every pending position, of every person; each row carries its own tenant and person. */
    public List<PositionRow> pendingClosePositions() {
        return jdbc.sql(POSITION_COLUMNS + " where status = 'CLOSE_PENDING' and mode = 'PAPER'"
                        + " order by bot_id, chain, token_mint")
                .query(JdbcLedgerRepository::position)
                .list();
    }

    public void updatePosition(
            UUID positionId, BigDecimal quantity, BigDecimal costBasis, PositionStatus status, Instant at) {
        jdbc.sql("""
                        update position set quantity = :quantity, cost_basis = :cost, status = :status,
                          updated_at = :at, version = version + 1
                        where position_id = :position
                        """)
                .param("quantity", quantity)
                .param("cost", costBasis)
                .param("status", status.name())
                .param("at", Timestamp.from(at))
                .param("position", positionId)
                .update();
    }

    // ---------------------------------------------------------------- daily summary

    /** Q1 of the NFR Design: one more fill of the (bot, token, UTC day), in the fill's transaction. */
    public void addDaily(
            AccountRow account,
            String chain,
            String mint,
            LocalDate day,
            BigDecimal realized,
            BigDecimal fees,
            Instant at) {
        jdbc.sql("""
                        insert into pnl_daily (tenant_id, owner_user_id, bot_id, mode, chain, token_mint, utc_day,
                          realized, fees, fill_count, updated_at)
                        values (:tenant, :user, :bot, 'PAPER', :chain, :mint, :day, :realized, :fees, 1, :at)
                        on conflict (bot_id, mode, chain, token_mint, utc_day) do update
                          set realized = pnl_daily.realized + excluded.realized,
                              fees = pnl_daily.fees + excluded.fees,
                              fill_count = pnl_daily.fill_count + 1,
                              updated_at = excluded.updated_at
                        """)
                .param("tenant", account.tenantId())
                .param("user", account.ownerUserId())
                .param("bot", account.botId())
                .param("chain", chain)
                .param("mint", mint)
                .param("day", day)
                .param("realized", realized)
                .param("fees", fees)
                .param("at", Timestamp.from(at))
                .update();
    }

    /** The compensation of a FEE: only the fees of the day of the original fill change; fill_count stays. */
    public void addDailyFees(UUID botId, String chain, String mint, LocalDate day, BigDecimal delta, Instant at) {
        int rows = jdbc.sql("""
                        update pnl_daily set fees = fees + :delta, updated_at = :at
                        where bot_id = :bot and mode = 'PAPER' and chain = :chain and token_mint = :mint
                          and utc_day = :day
                        """)
                .param("delta", delta)
                .param("at", Timestamp.from(at))
                .param("bot", botId)
                .param("chain", chain)
                .param("mint", mint)
                .param("day", day)
                .update();
        if (rows != 1) {
            throw new IllegalStateException("No daily summary row for the compensated fee of bot " + botId);
        }
    }

    // ---------------------------------------------------------------- mapping

    static final String ORDER_COLUMNS = """
            select order_id, tenant_id, owner_user_id, bot_id, idempotency_key, content_digest, kind, side, chain,
                   token_mint, requested_quantity, limits_version, status, not_filled_reason, created_at
            from paper_order""";

    static final String POSITION_COLUMNS = """
            select position_id, tenant_id, owner_user_id, bot_id, chain, token_mint, token_decimals, quantity,
                   cost_basis, status, updated_at, version
            from position""";

    static final String FILL_COLUMNS = """
            select f.fill_id, f.filled_quantity, f.reference_price, f.quoted_price, f.fill_price, f.spread_pct,
                   f.impact_pct, f.slippage_pct, f.liquidity_consumed_pct, f.liquidity_used, f.gross_amount, f.fees,
                   f.realized, f.market_source, f.market_observed_at, f.simulation_policy_version, f.filled_at""";

    static AccountRow account(ResultSet rs, int row) throws SQLException {
        return new AccountRow(
                rs.getObject("bot_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("owner_user_id", UUID.class),
                AccountStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant());
    }

    static StoredOrder order(ResultSet rs, int row) throws SQLException {
        return new StoredOrder(
                rs.getObject("order_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("owner_user_id", UUID.class),
                rs.getObject("bot_id", UUID.class),
                rs.getString("idempotency_key"),
                rs.getString("content_digest"),
                rs.getString("kind"),
                rs.getString("side"),
                rs.getString("chain"),
                rs.getString("token_mint"),
                plain(rs.getBigDecimal("requested_quantity")),
                rs.getLong("limits_version"),
                rs.getString("status"),
                Optional.ofNullable(rs.getString("not_filled_reason")),
                rs.getTimestamp("created_at").toInstant());
    }

    static PositionRow position(ResultSet rs, int row) throws SQLException {
        int decimals = rs.getInt("token_decimals");
        return new PositionRow(
                rs.getObject("position_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("owner_user_id", UUID.class),
                rs.getObject("bot_id", UUID.class),
                rs.getString("chain"),
                rs.getString("token_mint"),
                decimals,
                rs.getBigDecimal("quantity").setScale(decimals, java.math.RoundingMode.DOWN),
                rs.getBigDecimal("cost_basis"),
                PositionStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version"));
    }

    static FillDetails fillDetails(ResultSet rs) throws SQLException {
        return new FillDetails(
                rs.getObject("fill_id", UUID.class),
                plain(rs.getBigDecimal("filled_quantity")),
                rs.getBigDecimal("reference_price"),
                rs.getBigDecimal("quoted_price"),
                rs.getBigDecimal("fill_price"),
                rs.getBigDecimal("spread_pct"),
                rs.getBigDecimal("impact_pct"),
                rs.getBigDecimal("slippage_pct"),
                rs.getBigDecimal("liquidity_consumed_pct"),
                rs.getBigDecimal("liquidity_used"),
                rs.getBigDecimal("gross_amount"),
                rs.getBigDecimal("fees"),
                Optional.ofNullable(rs.getBigDecimal("realized")),
                rs.getString("market_source"),
                rs.getTimestamp("market_observed_at").toInstant(),
                rs.getString("simulation_policy_version"),
                rs.getTimestamp("filled_at").toInstant());
    }

    /** A token quantity without the trailing zeros of numeric(38,18), never in scientific notation. */
    static BigDecimal plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
