package com.memetrade.testsupport;

import com.memetrade.identity.api.Principal;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Raw rows of the U7 tables written with a given login (normally the application login), for the database tests of
 * the migration 0008: privileges, triggers, checks and unique keys. Values are synthetic.
 */
public final class LedgerRows {

    public static final Timestamp AT = Timestamp.from(Instant.parse("2026-10-28T12:00:00Z"));

    private final JdbcClient db;

    public LedgerRows(JdbcClient db) {
        this.db = db;
    }

    public UUID account(Principal person, String status) {
        UUID botId = UUID.randomUUID();
        db.sql("""
                        insert into paper_account (bot_id, tenant_id, owner_user_id, mode, status, created_at)
                        values (:bot, :tenant, :user, 'PAPER', :status, :at)
                        """)
                .param("bot", botId)
                .param("tenant", person.tenantId())
                .param("user", person.userId())
                .param("status", status)
                .param("at", AT)
                .update();
        return botId;
    }

    public UUID order(Principal person, UUID botId, String kind, String status, String reason, String key) {
        UUID orderId = UUID.randomUUID();
        db.sql("""
                        insert into paper_order (order_id, tenant_id, owner_user_id, bot_id, mode, decision_id,
                          idempotency_key, content_digest, kind, side, chain, token_mint, requested_quantity,
                          limits_version, status, not_filled_reason, created_at)
                        values (:order, :tenant, :user, :bot, 'PAPER', :decision, :key, :digest, :kind, 'BUY',
                          'solana', 'MintRowsTest', 1, 1, :status, :reason, :at)
                        """)
                .param("order", orderId)
                .param("tenant", person.tenantId())
                .param("user", person.userId())
                .param("bot", botId)
                .param("decision", "TRADE".equals(kind) ? UUID.randomUUID() : null)
                .param("key", key)
                .param("digest", "0".repeat(64))
                .param("kind", kind)
                .param("status", status)
                .param("reason", reason)
                .param("at", AT)
                .update();
        return orderId;
    }

    public UUID fill(Principal person, UUID botId, UUID orderId) {
        UUID fillId = UUID.randomUUID();
        db.sql("""
                        insert into fill (fill_id, order_id, tenant_id, owner_user_id, bot_id, mode, chain, token_mint,
                          filled_quantity, reference_price, quoted_price, fill_price, spread_pct, impact_pct,
                          slippage_pct, liquidity_consumed_pct, liquidity_used, gross_amount, fees, realized,
                          market_source, market_observed_at, simulation_policy_version, filled_at)
                        values (:fill, :order, :tenant, :user, :bot, 'PAPER', 'solana', 'MintRowsTest', 1, 1, 1.005,
                          1.006, 0.5, 0.1, 0.6, 0.1, 100000, 1.006, 0.003018, null, 'sim-u7', :at, 'v1', :at)
                        """)
                .param("fill", fillId)
                .param("order", orderId)
                .param("tenant", person.tenantId())
                .param("user", person.userId())
                .param("bot", botId)
                .param("at", AT)
                .update();
        return fillId;
    }

    public UUID entry(Principal person, UUID botId, String type, String amount, String reference, UUID compensates) {
        UUID entryId = UUID.randomUUID();
        db.sql("""
                        insert into ledger_entry (entry_id, tenant_id, owner_user_id, bot_id, mode, type, asset, amount,
                          reference_id, compensates_entry_id, created_at)
                        values (:entry, :tenant, :user, :bot, 'PAPER', :type, 'USDC', :amount, :reference,
                          :compensates, :at)
                        """)
                .param("entry", entryId)
                .param("tenant", person.tenantId())
                .param("user", person.userId())
                .param("bot", botId)
                .param("type", type)
                .param("amount", new BigDecimal(amount))
                .param("reference", reference)
                .param("compensates", compensates)
                .param("at", AT)
                .update();
        return entryId;
    }

    public UUID position(Principal person, UUID botId, String quantity, String cost, String status) {
        UUID positionId = UUID.randomUUID();
        db.sql("""
                        insert into position (position_id, tenant_id, owner_user_id, bot_id, mode, chain, token_mint,
                          token_decimals, quantity, cost_basis, status, updated_at, version)
                        values (:position, :tenant, :user, :bot, 'PAPER', 'solana', :mint, 6, :quantity, :cost,
                          :status, :at, 0)
                        """)
                .param("position", positionId)
                .param("tenant", person.tenantId())
                .param("user", person.userId())
                .param("bot", botId)
                .param("mint", SyntheticIds.mint())
                .param("quantity", new BigDecimal(quantity))
                .param("cost", new BigDecimal(cost))
                .param("status", status)
                .param("at", AT)
                .update();
        return positionId;
    }

    public void daily(Principal person, UUID botId, LocalDate day, String realized, String fees, int count) {
        db.sql("""
                        insert into pnl_daily (tenant_id, owner_user_id, bot_id, mode, chain, token_mint, utc_day,
                          realized, fees, fill_count, updated_at)
                        values (:tenant, :user, :bot, 'PAPER', 'solana', 'MintRowsTest', :day, :realized, :fees,
                          :count, :at)
                        """)
                .param("tenant", person.tenantId())
                .param("user", person.userId())
                .param("bot", botId)
                .param("day", day)
                .param("realized", new BigDecimal(realized))
                .param("fees", new BigDecimal(fees))
                .param("count", count)
                .param("at", AT)
                .update();
    }

    public void peak(Principal person, UUID botId, String peak) {
        db.sql("""
                        insert into equity_peak (bot_id, tenant_id, owner_user_id, mode, peak_equity, peak_at,
                          last_equity, last_equity_at)
                        values (:bot, :tenant, :user, 'PAPER', :peak, :at, :peak, :at)
                        """)
                .param("bot", botId)
                .param("tenant", person.tenantId())
                .param("user", person.userId())
                .param("peak", new BigDecimal(peak))
                .param("at", AT)
                .update();
    }

    public UUID check(Principal person) {
        UUID checkId = UUID.randomUUID();
        db.sql("""
                        insert into reconciliation_check (check_id, tenant_id, owner_user_id, mode, status,
                          divergence_count, divergences, check_trigger, checked_at)
                        values (:check, :tenant, :user, 'PAPER', 'RECONCILED', 0, '[]'::jsonb, 'SCHEDULED', :at)
                        """)
                .param("check", checkId)
                .param("tenant", person.tenantId())
                .param("user", person.userId())
                .param("at", AT)
                .update();
        return checkId;
    }

    /** The SQLSTATE of the first {@link java.sql.SQLException} in the chain, or {@code "none"}. */
    public static String sqlState(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return "none";
    }
}
