package com.memetrade.ledger.infra;

import com.memetrade.ledger.api.RealBalance;
import com.memetrade.ledger.api.RealEntry;
import com.memetrade.ledger.api.RealEntryKind;
import com.memetrade.ledger.api.RealFlows;
import com.memetrade.ledger.api.RealLedger;
import com.memetrade.ledger.api.RealLedgerView;
import com.memetrade.ledger.api.RealPosition;
import com.memetrade.ledger.domain.RealBalances;
import com.memetrade.ledger.domain.RealEntryRules;
import com.memetrade.ledger.domain.RealLedgerRow;
import com.memetrade.ledger.domain.RealPositions;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JDBC adapter of M2 over {@code real_ledger_entry}: inserts with {@code ON CONFLICT (idempotency_key) DO NOTHING} in
 * the caller's transaction; the balance groups the entries by kind and reference over the covering index. Every query
 * filters by tenant and wallet (BR6.4); the table only ever holds REAL rows (BR6.3).
 */
@Repository
public class JdbcRealLedger implements RealLedger {

    /** U7 (U7-NFR3.3): the rows of the fiscal export reach the writer 500 at a time. */
    static final int STREAM_FETCH_SIZE = 500;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate named;

    public JdbcRealLedger(JdbcClient jdbc, Clock clock, javax.sql.DataSource dataSource) {
        this.jdbc = jdbc;
        this.clock = clock;
        org.springframework.jdbc.core.JdbcTemplate template =
                new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        template.setFetchSize(STREAM_FETCH_SIZE);
        this.named = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(template);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public void streamEntries(
            UUID tenantId,
            java.util.Collection<UUID> botWalletIds,
            Instant from,
            Instant to,
            java.util.function.Consumer<com.memetrade.ledger.api.RealEntryRecord> consumer) {
        if (botWalletIds.isEmpty()) {
            return;
        }
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("tenant", tenantId);
        params.put("wallets", List.copyOf(botWalletIds));
        params.put("from", Timestamp.from(from));
        params.put("to", Timestamp.from(to));
        named.query("""
                select entry_kind, lamports, mint, token_base_units, tx_signature, reference, created_at
                from (
                  select e.*, min(e.created_at) over (partition by e.reference) as reference_at
                  from real_ledger_entry e
                  where e.tenant_id = :tenant and e.created_at >= :from and e.created_at < :to and e.mode = 'REAL'
                ) entries
                where bot_wallet_id in (:wallets)
                order by reference_at, reference, created_at, entry_id""", params, rs -> {
            BigDecimal units = rs.getBigDecimal("token_base_units");
            consumer.accept(new com.memetrade.ledger.api.RealEntryRecord(
                    RealEntryKind.valueOf(rs.getString("entry_kind")),
                    rs.getLong("lamports"),
                    Optional.ofNullable(rs.getString("mint")),
                    Optional.ofNullable(units).map(BigDecimal::longValueExact),
                    Optional.ofNullable(rs.getString("tx_signature")),
                    rs.getString("reference"),
                    rs.getTimestamp("created_at").toInstant()));
        });
    }

    @Override
    public boolean record(RealEntry entry) {
        RealEntryRules.validate(entry);
        return jdbc.sql("""
                                insert into real_ledger_entry (entry_id, tenant_id, bot_wallet_id, entry_kind, lamports,
                                  tx_signature, reference, idempotency_key, created_at, mint, token_base_units)
                                values (:id, :tenant, :wallet, :kind, :lamports, :signature, :reference, :key, :at,
                                  :mint, :units)
                                on conflict (idempotency_key) do nothing""")
                        .param("id", UUID.randomUUID())
                        .param("tenant", entry.tenantId())
                        .param("wallet", entry.botWalletId())
                        .param("kind", entry.kind().name())
                        .param("lamports", entry.lamports())
                        .param("signature", entry.txSignature())
                        .param("reference", entry.reference())
                        .param("key", entry.idempotencyKey())
                        .param("at", Timestamp.from(clock.instant()))
                        .param("mint", entry.mint())
                        .param(
                                "units",
                                entry.tokenBaseUnits() == null ? null : BigDecimal.valueOf(entry.tokenBaseUnits()))
                        .update()
                == 1;
    }

    @Override
    public RealBalance balance(UUID tenantId, UUID botWalletId) {
        return RealBalances.of(jdbc.sql("""
                        select entry_kind, reference, sum(lamports) as lamports
                        from real_ledger_entry
                        where tenant_id = :tenant and bot_wallet_id = :wallet and mode = 'REAL'
                        group by entry_kind, reference""")
                .param("tenant", tenantId)
                .param("wallet", botWalletId)
                .query((rs, row) -> new RealLedgerRow(
                        RealEntryKind.valueOf(rs.getString("entry_kind")),
                        rs.getBigDecimal("lamports").longValueExact(),
                        rs.getString("reference")))
                .list());
    }

    @Override
    public List<RealPosition> positions(UUID tenantId, UUID botWalletId) {
        return RealPositions.of(jdbc.sql("""
                        select entry_kind, lamports, mint, token_base_units
                        from real_ledger_entry
                        where tenant_id = :tenant and bot_wallet_id = :wallet and mode = 'REAL'
                          and entry_kind in ('SWAP_FILL_IN', 'SWAP_FILL_OUT')
                        order by created_at, case entry_kind when 'SWAP_FILL_IN' then 0 else 1 end, entry_id""")
                .param("tenant", tenantId)
                .param("wallet", botWalletId)
                .query((rs, row) -> new RealPositions.Fill(
                        RealEntryKind.valueOf(rs.getString("entry_kind")),
                        rs.getLong("lamports"),
                        rs.getString("mint"),
                        rs.getBigDecimal("token_base_units").longValueExact()))
                .list());
    }

    @Override
    public RealFlows flowsSince(UUID tenantId, UUID botWalletId, Instant since) {
        return jdbc.sql("""
                        select
                          coalesce(sum(lamports) filter (where entry_kind = 'DEPOSIT'), 0) as deposited,
                          coalesce(-sum(lamports) filter (where entry_kind = 'WITHDRAWAL'
                            or (entry_kind = 'NETWORK_FEE' and reference like 'wd:%')), 0) as withdrawn
                        from real_ledger_entry
                        where tenant_id = :tenant and bot_wallet_id = :wallet and mode = 'REAL'
                          and created_at > :since""")
                .param("tenant", tenantId)
                .param("wallet", botWalletId)
                .param("since", Timestamp.from(since))
                .query((rs, row) -> new RealFlows(
                        rs.getBigDecimal("deposited").longValueExact(),
                        rs.getBigDecimal("withdrawn").longValueExact()))
                .single();
    }

    @Override
    public java.util.Map<String, Long> tokenBalances(UUID tenantId, UUID botWalletId) {
        java.util.Map<String, Long> balances = new java.util.TreeMap<>();
        jdbc.sql("""
                        select mint, sum(case entry_kind when 'SWAP_FILL_OUT' then -token_base_units
                          else token_base_units end) as units
                        from real_ledger_entry
                        where tenant_id = :tenant and bot_wallet_id = :wallet and mode = 'REAL' and mint is not null
                          and entry_kind in ('SWAP_FILL_IN', 'SWAP_FILL_OUT', 'COMPENSATION')
                        group by mint""").param("tenant", tenantId).param("wallet", botWalletId).query(rs -> {
            long units = rs.getBigDecimal("units").longValueExact();
            if (units != 0) {
                balances.put(rs.getString("mint"), units);
            }
        });
        return balances;
    }

    /** The balance rows (B), the fills in recording order (P) and, when asked, the flows (F), as one statement. */
    private static final String VIEW = """
            with entries as (
              select entry_id, entry_kind, lamports, mint, token_base_units, reference, created_at
              from real_ledger_entry
              where tenant_id = :tenant and bot_wallet_id = :wallet and mode = 'REAL'
            )
            select 'B' as part, entry_kind, reference, sum(lamports)::numeric as amount, null::numeric as other,
                   null::varchar as mint, null::timestamptz as created_at, 0 as kind_rank, null::uuid as entry_id
            from entries group by entry_kind, reference
            union all
            select 'P', entry_kind, null, lamports::numeric, token_base_units, mint, created_at,
                   case entry_kind when 'SWAP_FILL_IN' then 0 else 1 end, entry_id
            from entries where entry_kind in ('SWAP_FILL_IN', 'SWAP_FILL_OUT')
            """;

    private static final String VIEW_FLOWS = """
            union all
            select 'F', null, null,
                   coalesce(sum(lamports) filter (where entry_kind = 'DEPOSIT'), 0)::numeric,
                   coalesce(-sum(lamports) filter (where entry_kind = 'WITHDRAWAL'
                     or (entry_kind = 'NETWORK_FEE' and reference like 'wd:%')), 0)::numeric,
                   null, null, 0, null
            from entries where created_at > :since
            """;

    /**
     * 261113-trading-real-bot U3 (review R-01): the three reads of {@link #balance}, {@link #positions} and
     * {@link #flowsSince} in ONE statement over the same rows, so they share one snapshot of PostgreSQL even in READ
     * COMMITTED: a fill or a deposit committed meanwhile is in all three parts or in none.
     */
    @Override
    public RealLedgerView view(UUID tenantId, UUID botWalletId, Optional<Instant> flowsSince) {
        List<RealLedgerRow> balanceRows = new ArrayList<>();
        List<RealPositions.Fill> fills = new ArrayList<>();
        RealFlows[] flows = {RealFlows.NONE};
        String sql =
                VIEW + (flowsSince.isPresent() ? VIEW_FLOWS : "") + " order by part, created_at, kind_rank, entry_id";
        var statement = jdbc.sql(sql).param("tenant", tenantId).param("wallet", botWalletId);
        if (flowsSince.isPresent()) {
            statement = statement.param("since", Timestamp.from(flowsSince.get()));
        }
        statement.query(rs -> {
            switch (rs.getString("part")) {
                case "B" ->
                    balanceRows.add(new RealLedgerRow(
                            RealEntryKind.valueOf(rs.getString("entry_kind")),
                            rs.getBigDecimal("amount").longValueExact(),
                            rs.getString("reference")));
                case "P" ->
                    fills.add(new RealPositions.Fill(
                            RealEntryKind.valueOf(rs.getString("entry_kind")),
                            rs.getBigDecimal("amount").longValueExact(),
                            rs.getString("mint"),
                            rs.getBigDecimal("other").longValueExact()));
                default ->
                    flows[0] = new RealFlows(
                            rs.getBigDecimal("amount").longValueExact(),
                            rs.getBigDecimal("other").longValueExact());
            }
        });
        return new RealLedgerView(RealBalances.of(balanceRows), RealPositions.of(fills), flows[0]);
    }
}
