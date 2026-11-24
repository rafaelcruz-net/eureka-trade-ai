package com.memetrade.pnl;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 261121-paper-real-unico U3 (test support): the PAPER and REAL bots of the screens of the P&L written straight in the
 * database of the test: a PAPER bot with its account and fills at chosen instants, and a REAL bot of a chosen strategy.
 * Synthetic mints only; never a real wallet. Every bot is ACTIVE, created at the database clock.
 */
public final class PnlViewsRows {

    public static final String STRATEGY = "MOMENTUM_VOLUME";
    public static final String OTHER_STRATEGY = "RANGE_BREAKOUT";

    private final JdbcClient jdbc;

    public PnlViewsRows(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** An ACTIVE PAPER bot of the person, with its OPEN PAPER account. */
    public UUID paperBot(UUID tenantId, UUID userId, String name, String templateId) {
        UUID botId = UUID.randomUUID();
        owner(tenantId, userId);
        jdbc.sql("""
                        insert into paper_account (bot_id, tenant_id, owner_user_id, mode, status, created_at)
                        values (:bot, :tenant, :user, 'PAPER', 'OPEN', now())""")
                .param("bot", botId)
                .param("tenant", tenantId)
                .param("user", userId)
                .update();
        bot(botId, tenantId, userId, name, templateId, "PAPER");
        return botId;
    }

    /** An ACTIVE REAL bot of the person, of the strategy {@code templateId}. */
    public UUID realBot(UUID tenantId, UUID userId, String name, String templateId) {
        UUID botId = UUID.randomUUID();
        owner(tenantId, userId);
        bot(botId, tenantId, userId, name, templateId, "REAL");
        return botId;
    }

    /** A filled PAPER sale of the bot at {@code at}, with its realized and its fees in exact US$. */
    public void paperFill(UUID tenantId, UUID userId, UUID botId, Instant at, String realized, String fees) {
        UUID orderId = UUID.randomUUID();
        jdbc.sql("""
                        insert into paper_order (order_id, tenant_id, owner_user_id, bot_id, mode, decision_id,
                          idempotency_key, content_digest, kind, side, chain, token_mint, requested_quantity,
                          limits_version, status, not_filled_reason, created_at)
                        values (:order, :tenant, :user, :bot, 'PAPER', :decision, :key, :digest, 'TRADE', 'SELL',
                          'solana', 'MintU3Views', 1, 1, 'FILLED', null, :at)""")
                .param("order", orderId)
                .param("tenant", tenantId)
                .param("user", userId)
                .param("bot", botId)
                .param("decision", UUID.randomUUID())
                .param("key", "u3-" + orderId)
                .param("digest", "0".repeat(64))
                .param("at", Timestamp.from(at))
                .update();
        jdbc.sql("""
                        insert into fill (fill_id, order_id, tenant_id, owner_user_id, bot_id, mode, chain, token_mint,
                          filled_quantity, reference_price, quoted_price, fill_price, spread_pct, impact_pct,
                          slippage_pct, liquidity_consumed_pct, liquidity_used, gross_amount, fees, realized,
                          market_source, market_observed_at, simulation_policy_version, filled_at)
                        values (gen_random_uuid(), :order, :tenant, :user, :bot, 'PAPER', 'solana', 'MintU3Views', 1, 1,
                          1, 1, 0, 0, 0, 0, 1000, 1, :fees, :realized, 'sim-u3', :at, 'v1', :at)""")
                .param("order", orderId)
                .param("tenant", tenantId)
                .param("user", userId)
                .param("bot", botId)
                .param("fees", new BigDecimal(fees))
                .param("realized", new BigDecimal(realized))
                .param("at", Timestamp.from(at))
                .update();
    }

    /** The public address of the bot wallet of the person (it must never be in an answer). */
    public String botWalletAddress(UUID botWalletId) {
        return jdbc.sql("select public_address from bot_wallet_account where bot_wallet_id = :id")
                .param("id", botWalletId)
                .query(String.class)
                .single();
    }

    /** Rows of the tables the reads of the U3 touch, all added up (a read writes nothing). */
    public long rowsOfTheReads() {
        return jdbc.sql("""
                        select (select count(*) from bot) + (select count(*) from pnl_daily)
                          + (select count(*) from idempotency_record) + (select count(*) from real_ledger_entry)
                          + (select count(*) from real_order) + (select count(*) from fill)""").query(Long.class).single();
    }

    private void owner(UUID tenantId, UUID userId) {
        jdbc.sql("insert into bot_owner (tenant_id, owner_user_id, created_at) values (:t, :u, now())"
                        + " on conflict do nothing")
                .param("t", tenantId)
                .param("u", userId)
                .update();
    }

    private void bot(UUID botId, UUID tenantId, UUID userId, String name, String templateId, String market) {
        jdbc.sql("""
                        insert into bot (bot_id, tenant_id, owner_user_id, name, template_id, template_version, state,
                          created_at, updated_at, market)
                        values (:b, :t, :u, :name, :template, 'v1', 'ACTIVE', clock_timestamp(), clock_timestamp(),
                          :market)""")
                .param("b", botId)
                .param("t", tenantId)
                .param("u", userId)
                .param("name", name)
                .param("template", templateId)
                .param("market", market)
                .update();
    }
}
