package com.memetrade.testsupport;

import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A database of its own for the volume of NFR2.1 (U7): created in the shared PostgreSQL container by the superuser,
 * prepared by the versioned role bootstrap and migrated by the OWNER through the production db-migrate path, exactly
 * like {@link AbstractPostgresIT} prepares the shared one. Kept apart so the 200 000 fills never slow down, nor enter,
 * the reconciliation rounds of the other integration tests (they never depend on shared state).
 *
 * <p>{@link #seed} writes, with set-based SQL, a ledger that is consistent by construction with
 * BR2.2, BR2.3 and the daily summary: per person 3 bots x 5 tokens and 10 000 fills (buy and sell pairs of 100 units
 * per position; 10 positions end with an open buy, 5 end closed), 3 entries per fill (cost or proceeds, slippage and
 * fee, summing to {@code -(gross + fee)} or {@code gross - fee}), one FUNDING per bot, the positions equal to the
 * replay of their fills and {@code pnl_daily} equal to the direct sum per UTC day. Synthetic values only; every row is
 * PAPER.
 */
public final class U7VolumeDatabase {

    public static final int PEOPLE = 20;
    public static final int BOTS_PER_PERSON = 3;
    public static final int TOKENS_PER_BOT = 5;
    public static final int FILLS_PER_PERSON = 10_000;

    private static String url;

    private U7VolumeDatabase() {}

    /** The JDBC URL of the volume database, created and migrated on first use (once per JVM). */
    public static synchronized String url() {
        if (url == null) {
            String name = "u7_volume_" + UUID.randomUUID().toString().replace("-", "");
            AbstractPostgresIT.connect(AbstractPostgresIT.SUPERUSER)
                    .sql("create database " + name)
                    .update();
            String created = AbstractPostgresIT.POSTGRES.getJdbcUrl().replace("/memetrade", "/" + name);
            AbstractPostgresIT.bootstrap(name);
            AbstractPostgresIT.migrate(created);
            url = created;
        }
        return url;
    }

    /** The OWNER login on the volume database (one dedicated connection, auto-commit). */
    public static JdbcClient owner() {
        return AbstractPostgresIT.connect(AbstractPostgresIT.OWNER, url());
    }

    /** A seeded person and the wallet it signs in with. */
    public record Person(Principal principal, TestWallet wallet) {}

    /**
     * Seeds {@link #PEOPLE} people with the whole volume. Runs once; the caller keeps the returned people.
     *
     * @return the people, each with a generated test wallet (never a real one)
     */
    public static List<Person> seed() {
        // The container superuser of the tests: the bootstrap gives the OWNER no TEMP on the database, and the seed
        // plans its rows in temporary tables. Triggers and checks of the migration still apply to every row.
        JdbcClient owner = AbstractPostgresIT.connect(AbstractPostgresIT.SUPERUSER, url());
        List<Person> people = new ArrayList<>();
        owner.sql("create temporary table vol_person (p int, tenant_id uuid, owner_user_id uuid)")
                .update();
        for (int p = 0; p < PEOPLE; p++) {
            TestWallet wallet = TestWallet.generate();
            TestAccounts.Seeded seeded = TestAccounts.betaUser(owner, wallet.address());
            owner.sql("insert into vol_person values (:p, :tenant, :user)")
                    .param("p", p)
                    .param("tenant", seeded.tenantId())
                    .param("user", seeded.userId())
                    .update();
            people.add(new Person(new Principal(seeded.tenantId(), seeded.userId(), Role.BETA_USER), wallet));
        }
        for (String statement : STATEMENTS) {
            owner.sql(statement).update();
        }
        return people;
    }

    /** Number of rows of {@code table} (read with the OWNER login). */
    public static long count(String table) {
        return owner().sql("select count(*) from " + table).query(Long.class).single();
    }

    /** Gross amount of the buy at step {@code s} of a position (odd steps buy, the next even step sells it). */
    private static final String BUY_GROSS = "(100.000000 + ((%s) %% 7) * 0.500000)";

    private static final List<String> STATEMENTS = List.of(
            // 60 bots, one PAPER account and one FUNDING each.
            """
            create temporary table vol_bot as
            select p.tenant_id, p.owner_user_id, b, gen_random_uuid() as bot_id
            from vol_person p cross join generate_series(0, %d) b
            """.formatted(BOTS_PER_PERSON - 1),
            """
            insert into paper_account (bot_id, tenant_id, owner_user_id, mode, status, created_at)
            select bot_id, tenant_id, owner_user_id, 'PAPER', 'OPEN', timestamptz '2026-08-01T00:00:00Z' from vol_bot
            """,
            """
            insert into ledger_entry (entry_id, tenant_id, owner_user_id, bot_id, mode, type, asset, amount,
              reference_id, compensates_entry_id, created_at)
            select gen_random_uuid(), tenant_id, owner_user_id, bot_id, 'PAPER', 'FUNDING', 'USDC', 1000000.000000,
              'vol-fund-' || bot_id, null, timestamptz '2026-08-01T00:00:00Z'
            from vol_bot
            """,
            // 15 positions per person: the first 10 get 667 fills (ending open), the other 5 get 666 (ending closed).
            """
            create temporary table vol_position as
            select vb.*, t, gen_random_uuid() as position_id,
                   'VolMint' || substr(md5(vb.bot_id::text || ':' || t), 1, 24) as token_mint,
                   case when vb.b * %d + t < 10 then 667 else 666 end as fills
            from vol_bot vb cross join generate_series(0, %d) t
            """.formatted(TOKENS_PER_BOT, TOKENS_PER_BOT - 1),
            """
            create temporary table vol_fill as
            select x.*,
                   round(x.gross * 0.003, 6) as fees,
                   case when x.side = 'SELL' then x.gross - x.buy_gross end as realized
            from (
              select pos.tenant_id, pos.owner_user_id, pos.bot_id, pos.token_mint, s,
                     gen_random_uuid() as order_id, gen_random_uuid() as fill_id,
                     case when s %% 2 = 1 then 'BUY' else 'SELL' end as side,
                     %s as buy_gross,
                     case when s %% 2 = 1 then %s
                          else %s + ((s %% 5) - 2) * 0.250000 end as gross,
                     timestamptz '2026-08-01T00:00:00Z' + s * interval '3 hours' + pos.t * interval '1 minute'
                       as filled_at
              from vol_position pos cross join lateral generate_series(1, pos.fills) s
            ) x
            """.formatted(
                            BUY_GROSS.formatted("case when s % 2 = 1 then s else s - 1 end"),
                            BUY_GROSS.formatted("s"),
                            BUY_GROSS.formatted("s - 1")),
            """
            insert into paper_order (order_id, tenant_id, owner_user_id, bot_id, mode, decision_id, idempotency_key,
              content_digest, kind, side, chain, token_mint, requested_quantity, limits_version, status,
              not_filled_reason, created_at)
            select order_id, tenant_id, owner_user_id, bot_id, 'PAPER', gen_random_uuid(), 'vol-' || order_id,
              md5(order_id::text) || md5(fill_id::text), 'TRADE', side, 'solana', token_mint, 100, 1, 'FILLED', null,
              filled_at
            from vol_fill
            """,
            """
            insert into fill (fill_id, order_id, tenant_id, owner_user_id, bot_id, mode, chain, token_mint,
              filled_quantity, reference_price, quoted_price, fill_price, spread_pct, impact_pct, slippage_pct,
              liquidity_consumed_pct, liquidity_used, gross_amount, fees, realized, market_source,
              market_observed_at, simulation_policy_version, filled_at)
            select fill_id, order_id, tenant_id, owner_user_id, bot_id, 'PAPER', 'solana', token_mint, 100,
              round(gross / 100, 12), round(gross / 100, 12), round(gross / 100, 12), 0.5000, 0.1000, 0.6000, 0.1000,
              100000.000000, gross, fees, realized, 'sim-volume', filled_at, 'v1', filled_at
            from vol_fill
            order by bot_id, token_mint, s
            """,
            // BR2.2: a buy is -(gross + fee), a sell gross - fee, always in 3 entries.
            """
            insert into ledger_entry (entry_id, tenant_id, owner_user_id, bot_id, mode, type, asset, amount,
              reference_id, compensates_entry_id, created_at)
            select gen_random_uuid(), f.tenant_id, f.owner_user_id, f.bot_id, 'PAPER', e.type, 'USDC', e.amount,
              f.order_id::text, null, f.filled_at
            from vol_fill f cross join lateral (values
              (case when f.side = 'BUY' then 'TRADE_COST' else 'TRADE_PROCEEDS' end,
               case when f.side = 'BUY' then -(f.gross - 0.010000) else f.gross + 0.010000 end),
              ('SLIPPAGE', -0.010000),
              ('FEE', -f.fees)) as e(type, amount)
            """,
            // The replay of buy and sell pairs of the same quantity: empty after each sell, the last buy when odd.
            """
            insert into position (position_id, tenant_id, owner_user_id, bot_id, mode, chain, token_mint,
              token_decimals, quantity, cost_basis, status, updated_at, version)
            select position_id, tenant_id, owner_user_id, bot_id, 'PAPER', 'solana', token_mint, 6,
              case when fills %% 2 = 1 then 100 else 0 end,
              case when fills %% 2 = 1 then %s else 0 end,
              case when fills %% 2 = 1 then 'OPEN' else 'CLOSED' end,
              timestamptz '2026-08-01T00:00:00Z' + fills * interval '3 hours', fills
            from vol_position
            """.formatted(BUY_GROSS.formatted("fills")),
            """
            insert into pnl_daily (tenant_id, owner_user_id, bot_id, mode, chain, token_mint, utc_day, realized, fees,
              fill_count, updated_at)
            select tenant_id, owner_user_id, bot_id, 'PAPER', 'solana', token_mint,
              (filled_at at time zone 'UTC')::date, sum(coalesce(realized, 0)), sum(fees), count(*), max(filled_at)
            from vol_fill
            group by tenant_id, owner_user_id, bot_id, token_mint, (filled_at at time zone 'UTC')::date
            """,
            "analyze paper_account, paper_order, fill, ledger_entry, position, pnl_daily",
            "drop table vol_fill, vol_position, vol_bot, vol_person");
}
