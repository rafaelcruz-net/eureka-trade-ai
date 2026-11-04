package com.memetrade.ledger.infra;

import com.memetrade.ledger.api.LedgerReadModel;
import com.memetrade.ledger.api.PaperEnums.EntryType;
import com.memetrade.ledger.api.PaperEnums.NotFilledReason;
import com.memetrade.ledger.api.PaperEnums.OrderKind;
import com.memetrade.ledger.api.PaperEnums.OrderSide;
import com.memetrade.ledger.api.PaperEnums.OrderStatus;
import com.memetrade.ledger.api.PaperExecution.FillDetails;
import com.memetrade.ledger.infra.JdbcLedgerRepository.AccountRow;
import com.memetrade.ledger.infra.JdbcLedgerRepository.PositionRow;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The public read model of the ledger ({@link LedgerReadModel}). Every customer query filters by tenant, person and
 * PAPER (BR9.1, BR9.2); the cursors are tuples, never {@code OFFSET} (NFR2.3, NFR3.2).
 */
@Component
public class LedgerReadModelImpl implements LedgerReadModel {

    /** Fees of an order net of the compensations of its FEE entries (the join of performance-design.md). */
    static final String NET_FEES = """
            f.fees - coalesce((select sum(c.amount) from ledger_entry c
                               join ledger_entry fe on fe.entry_id = c.compensates_entry_id
                               where fe.reference_id = f.order_id::text and fe.type = 'FEE'), 0)""";

    private final JdbcClient jdbc;
    private final JdbcLedgerRepository repository;
    private final ReconciliationQueries reconciliation;

    public LedgerReadModelImpl(JdbcClient jdbc, JdbcLedgerRepository repository, ReconciliationQueries reconciliation) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.reconciliation = reconciliation;
    }

    @Override
    public Optional<AccountView> account(UUID botId) {
        return repository.findAccount(botId).map(LedgerReadModelImpl::view);
    }

    @Override
    public BigDecimal balance(UUID botId) {
        return repository.balance(botId);
    }

    @Override
    public List<PositionView> positions(UUID botId) {
        return repository.positions(botId).stream()
                .map(LedgerReadModelImpl::view)
                .toList();
    }

    @Override
    public List<PositionView> openPositions(UUID tenantId, UUID ownerUserId, Optional<UUID> botId) {
        return jdbc
                .sql(JdbcLedgerRepository.POSITION_COLUMNS
                        + " p where p.tenant_id = :tenant and p.owner_user_id = :user and p.mode = 'PAPER'"
                        + " and p.quantity > 0" + botFilter(botId, "p") + " order by p.bot_id, p.chain, p.token_mint")
                .param("tenant", tenantId)
                .param("user", ownerUserId)
                .params(botParam(botId))
                .query(JdbcLedgerRepository::position)
                .list()
                .stream()
                .map(LedgerReadModelImpl::view)
                .toList();
    }

    @Override
    public List<FillView> fills(UUID tenantId, UUID ownerUserId, Optional<UUID> botId, Instant from, Instant to) {
        return jdbc.sql("select f.order_id, f.bot_id, o.kind, o.side, f.chain, f.token_mint, f.filled_quantity,"
                        + " coalesce(f.realized, 0) as realized, " + NET_FEES + " as net_fees, f.filled_at"
                        + " from fill f join paper_order o on o.order_id = f.order_id"
                        + " where f.tenant_id = :tenant and f.owner_user_id = :user and f.mode = 'PAPER'"
                        + " and f.filled_at >= :from and f.filled_at < :to"
                        + botFilter(botId, "f")
                        + " order by f.filled_at, f.fill_seq")
                .param("tenant", tenantId)
                .param("user", ownerUserId)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .params(botParam(botId))
                .query((rs, row) -> new FillView(
                        rs.getObject("order_id", UUID.class),
                        rs.getObject("bot_id", UUID.class),
                        OrderKind.valueOf(rs.getString("kind")),
                        OrderSide.valueOf(rs.getString("side")),
                        rs.getString("chain"),
                        rs.getString("token_mint"),
                        JdbcLedgerRepository.plain(rs.getBigDecimal("filled_quantity")),
                        rs.getBigDecimal("realized").setScale(6),
                        rs.getBigDecimal("net_fees").setScale(6),
                        rs.getTimestamp("filled_at").toInstant()))
                .list();
    }

    @Override
    public List<DailySummaryRow> dailySummary(
            UUID tenantId, UUID ownerUserId, Optional<UUID> botId, LocalDate fromDay, LocalDate toDay) {
        return jdbc.sql("select bot_id, chain, token_mint, utc_day, realized, fees, fill_count from pnl_daily d"
                        + " where d.tenant_id = :tenant and d.owner_user_id = :user and d.mode = 'PAPER'"
                        + " and d.utc_day >= :from and d.utc_day < :to" + botFilter(botId, "d")
                        + " order by utc_day, chain, token_mint")
                .param("tenant", tenantId)
                .param("user", ownerUserId)
                .param("from", fromDay)
                .param("to", toDay)
                .params(botParam(botId))
                .query((rs, row) -> new DailySummaryRow(
                        rs.getObject("bot_id", UUID.class),
                        rs.getString("chain"),
                        rs.getString("token_mint"),
                        rs.getObject("utc_day", LocalDate.class),
                        rs.getBigDecimal("realized"),
                        rs.getBigDecimal("fees"),
                        rs.getInt("fill_count")))
                .list();
    }

    @Override
    public EntryPage entries(
            UUID tenantId, UUID ownerUserId, Optional<UUID> botId, Optional<EntryKey> after, int limit) {
        String where =
                " where e.tenant_id = :tenant and e.owner_user_id = :user and e.mode = 'PAPER'" + botFilter(botId, "e");
        String cursor = after.isPresent() ? " and (e.created_at, e.entry_id) < (:cursorAt, :cursorId)" : "";
        var statement = jdbc.sql("""
                        select e.entry_id, e.bot_id, e.type, e.asset, e.amount, e.reference_id, e.compensates_entry_id,
                               e.created_at, coalesce(f.order_id, cf.order_id) as order_id,
                               coalesce(f.simulation_policy_version, cf.simulation_policy_version) as policy
                        from ledger_entry e
                        left join fill f on e.type <> 'COMPENSATION' and f.order_id::text = e.reference_id
                        left join ledger_entry orig on orig.entry_id = e.compensates_entry_id
                        left join fill cf on cf.order_id::text = orig.reference_id
                        """ + where + cursor + " order by e.created_at desc, e.entry_id desc limit :limit")
                .param("tenant", tenantId)
                .param("user", ownerUserId)
                .param("limit", limit + 1)
                .params(botParam(botId));
        if (after.isPresent()) {
            statement = statement
                    .param("cursorAt", Timestamp.from(after.get().createdAt()))
                    .param("cursorId", after.get().entryId());
        }
        List<EntryView> items = statement.query((rs, row) -> entry(rs)).list();
        long total = jdbc.sql("select count(*) from ledger_entry e" + where)
                .param("tenant", tenantId)
                .param("user", ownerUserId)
                .params(botParam(botId))
                .query(Long.class)
                .single();
        if (items.size() > limit) {
            EntryView last = items.get(limit - 1);
            return new EntryPage(
                    items.subList(0, limit), Optional.of(new EntryKey(last.createdAt(), last.entryId())), total);
        }
        return new EntryPage(items, Optional.empty(), total);
    }

    @Override
    public TradePage trades(UUID tenantId, UUID ownerUserId, UUID botId, Optional<TradeKey> after, int limit) {
        String where = " where o.tenant_id = :tenant and o.owner_user_id = :user and o.mode = 'PAPER'"
                + " and o.bot_id = :bot";
        String cursor = after.isPresent() ? " and (o.created_at, o.order_id) < (:cursorAt, :cursorId)" : "";
        var statement = jdbc.sql("select o.order_id, o.bot_id, o.kind, o.side, o.chain, o.token_mint, o.status,"
                        + " o.not_filled_reason, o.requested_quantity, o.idempotency_key, o.limits_version,"
                        + " o.created_at, f.fill_id, "
                        + JdbcLedgerRepository.FILL_COLUMNS
                                .replace("select ", "")
                                .replace("f.fill_id, ", "")
                        + " from paper_order o left join fill f on f.order_id = o.order_id"
                        + where + cursor + " order by o.created_at desc, o.order_id desc limit :limit")
                .param("tenant", tenantId)
                .param("user", ownerUserId)
                .param("bot", botId)
                .param("limit", limit + 1);
        if (after.isPresent()) {
            statement = statement
                    .param("cursorAt", Timestamp.from(after.get().createdAt()))
                    .param("cursorId", after.get().orderId());
        }
        List<TradeView> items = statement.query((rs, row) -> trade(rs)).list();
        long total = jdbc.sql("select count(*) from paper_order o" + where)
                .param("tenant", tenantId)
                .param("user", ownerUserId)
                .param("bot", botId)
                .query(Long.class)
                .single();
        if (items.size() > limit) {
            TradeView last = items.get(limit - 1);
            return new TradePage(
                    items.subList(0, limit), Optional.of(new TradeKey(last.createdAt(), last.orderId())), total);
        }
        return new TradePage(items, Optional.empty(), total);
    }

    @Override
    public Optional<Instant> lastFilledAt(UUID botId) {
        return jdbc.sql("select filled_at from fill where bot_id = :bot and mode = 'PAPER'"
                        + " order by filled_at desc limit 1")
                .param("bot", botId)
                .query(Timestamp.class)
                .optional()
                .map(Timestamp::toInstant);
    }

    @Override
    public List<AccountView> accountsToMark() {
        return jdbc
                .sql("select bot_id, tenant_id, owner_user_id, status, created_at from paper_account"
                        + " where mode = 'PAPER' and status in ('OPEN', 'CLOSING') order by bot_id")
                .query(JdbcLedgerRepository::account)
                .list()
                .stream()
                .map(LedgerReadModelImpl::view)
                .toList();
    }

    @Override
    public List<PersonRef> peopleWithAccounts() {
        return jdbc.sql("select distinct tenant_id, owner_user_id from paper_account where mode = 'PAPER'"
                        + " order by tenant_id, owner_user_id")
                .query((rs, row) ->
                        new PersonRef(rs.getObject("tenant_id", UUID.class), rs.getObject("owner_user_id", UUID.class)))
                .list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public MarkingSnapshot lockAccountForMarking(UUID botId, Duration lockTimeout) {
        repository.setLocalTimeouts(lockTimeout, Optional.empty());
        AccountRow account = repository
                .lockAccount(botId)
                .orElseThrow(() -> new IllegalStateException("No PAPER account for bot " + botId));
        return new MarkingSnapshot(
                view(account),
                repository.balance(botId),
                repository.positions(botId).stream()
                        .map(LedgerReadModelImpl::view)
                        .toList());
    }

    @Override
    public ReconciliationFacts reconciliationFacts(UUID tenantId, UUID ownerUserId) {
        return reconciliation.facts(tenantId, ownerUserId);
    }

    @Override
    public PolicyView policy() {
        return jdbc.sql("select version, spread_pct, fee_pct from simulation_policy order by activated_at desc limit 1")
                .query((rs, row) -> new PolicyView(
                        rs.getString("version"), rs.getBigDecimal("spread_pct"), rs.getBigDecimal("fee_pct")))
                .single();
    }

    private static String botFilter(Optional<UUID> botId, String alias) {
        return botId.isPresent() ? " and " + alias + ".bot_id = :bot" : "";
    }

    private static java.util.Map<String, Object> botParam(Optional<UUID> botId) {
        return botId.<java.util.Map<String, Object>>map(id -> java.util.Map.of("bot", id))
                .orElse(java.util.Map.of());
    }

    static AccountView view(AccountRow row) {
        return new AccountView(row.botId(), row.tenantId(), row.ownerUserId(), row.status(), row.createdAt());
    }

    static PositionView view(PositionRow row) {
        return new PositionView(
                row.positionId(),
                row.botId(),
                row.chain(),
                row.tokenMint(),
                row.tokenDecimals(),
                row.quantity(),
                row.costBasis(),
                row.status(),
                row.updatedAt());
    }

    private static EntryView entry(ResultSet rs) throws SQLException {
        return new EntryView(
                rs.getObject("entry_id", UUID.class),
                rs.getObject("bot_id", UUID.class),
                EntryType.valueOf(rs.getString("type")),
                rs.getString("asset"),
                rs.getBigDecimal("amount"),
                rs.getString("reference_id"),
                Optional.ofNullable(rs.getObject("compensates_entry_id", UUID.class)),
                Optional.ofNullable(rs.getObject("order_id", UUID.class)),
                Optional.ofNullable(rs.getString("policy")),
                rs.getTimestamp("created_at").toInstant());
    }

    private static TradeView trade(ResultSet rs) throws SQLException {
        Optional<FillDetails> fill = rs.getObject("fill_id", UUID.class) == null
                ? Optional.empty()
                : Optional.of(JdbcLedgerRepository.fillDetails(rs));
        return new TradeView(
                rs.getObject("order_id", UUID.class),
                rs.getObject("bot_id", UUID.class),
                OrderKind.valueOf(rs.getString("kind")),
                OrderSide.valueOf(rs.getString("side")),
                rs.getString("chain"),
                rs.getString("token_mint"),
                OrderStatus.valueOf(rs.getString("status")),
                Optional.ofNullable(rs.getString("not_filled_reason")).map(NotFilledReason::valueOf),
                JdbcLedgerRepository.plain(rs.getBigDecimal("requested_quantity")),
                rs.getString("idempotency_key"),
                rs.getLong("limits_version"),
                rs.getTimestamp("created_at").toInstant(),
                fill);
    }
}
