package com.memetrade.ledger.infra;

import com.memetrade.ledger.api.LedgerReadModel.OrderFacts;
import com.memetrade.ledger.api.LedgerReadModel.OrphanEntry;
import com.memetrade.ledger.api.LedgerReadModel.PositionFacts;
import com.memetrade.ledger.api.LedgerReadModel.ReconciliationFacts;
import com.memetrade.ledger.api.LedgerReadModel.SummaryFacts;
import com.memetrade.ledger.domain.PositionAccounting;
import com.memetrade.ledger.domain.PositionAccounting.Holding;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The facts the reconciliation of one person compares (BR8.2, NFR3.6), every query filtered by tenant, person and
 * PAPER: orders against their entries (with the compensations joined through {@code compensates_entry_id}, the
 * original entry and its order), orphan entries, positions against the REPLAY of their fills in {@code filledAt}
 * order (the domain accounting), and the daily summary against the direct sum of the fills. The caller holds one
 * REPEATABLE READ snapshot.
 */
@Component
public class ReconciliationQueries {

    private static final String PERSON = " tenant_id = :tenant and owner_user_id = :user and mode = 'PAPER'";

    private final JdbcClient jdbc;

    public ReconciliationQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public ReconciliationFacts facts(UUID tenantId, UUID ownerUserId) {
        return new ReconciliationFacts(
                orders(tenantId, ownerUserId),
                orphans(tenantId, ownerUserId),
                positions(tenantId, ownerUserId),
                summaries(tenantId, ownerUserId));
    }

    /** Expected by BR2.2 (buy {@code -(gross + fee)}, sell and close {@code gross - fee}) plus the compensations. */
    private List<OrderFacts> orders(UUID tenantId, UUID ownerUserId) {
        return jdbc.sql("""
                        select o.order_id, o.bot_id, o.kind, o.side, f.gross_amount, f.fees,
                               (select count(*) from ledger_entry e
                                where e.reference_id = o.order_id::text and e.type <> 'COMPENSATION') as entry_count,
                               (select coalesce(sum(e.amount), 0) from ledger_entry e
                                where e.reference_id = o.order_id::text and e.type <> 'COMPENSATION') as entry_sum,
                               (select coalesce(sum(c.amount), 0) from ledger_entry c
                                join ledger_entry e on e.entry_id = c.compensates_entry_id
                                where e.reference_id = o.order_id::text) as compensations
                        from paper_order o left join fill f on f.order_id = o.order_id
                        where o.tenant_id = :tenant and o.owner_user_id = :user and o.mode = 'PAPER'
                          and o.status in ('FILLED', 'PARTIALLY_FILLED')
                        order by o.created_at, o.order_id
                        """)
                .param("tenant", tenantId)
                .param("user", ownerUserId)
                .query((rs, row) -> {
                    BigDecimal gross = rs.getBigDecimal("gross_amount");
                    BigDecimal fees = rs.getBigDecimal("fees");
                    BigDecimal compensations = rs.getBigDecimal("compensations");
                    BigDecimal expected = gross == null
                            ? BigDecimal.ZERO
                            : "BUY".equals(rs.getString("side")) && "TRADE".equals(rs.getString("kind"))
                                    ? gross.add(fees).negate()
                                    : gross.subtract(fees);
                    return new OrderFacts(
                            rs.getObject("order_id", UUID.class),
                            rs.getObject("bot_id", UUID.class),
                            rs.getInt("entry_count"),
                            expected.add(compensations),
                            rs.getBigDecimal("entry_sum").add(compensations));
                })
                .list();
    }

    /** An entry that is neither a FUNDING (its reference is the key) nor a COMPENSATION and references no order. */
    private List<OrphanEntry> orphans(UUID tenantId, UUID ownerUserId) {
        return jdbc.sql("select e.entry_id, e.bot_id, e.amount from ledger_entry e"
                        + " where e.tenant_id = :tenant and e.owner_user_id = :user and e.mode = 'PAPER'"
                        + " and e.type not in ('FUNDING', 'COMPENSATION')"
                        + " and not exists (select 1 from paper_order o where o.order_id::text = e.reference_id"
                        + " and o.bot_id = e.bot_id)"
                        + " order by e.created_at, e.entry_id")
                .param("tenant", tenantId)
                .param("user", ownerUserId)
                .query((rs, row) -> new OrphanEntry(
                        rs.getObject("entry_id", UUID.class),
                        rs.getObject("bot_id", UUID.class),
                        rs.getBigDecimal("amount")))
                .list();
    }

    private List<PositionFacts> positions(UUID tenantId, UUID ownerUserId) {
        List<PositionFacts> facts = new ArrayList<>();
        List<JdbcLedgerRepository.PositionRow> positions = jdbc.sql(JdbcLedgerRepository.POSITION_COLUMNS + " where"
                        + PERSON + " order by bot_id, chain, token_mint")
                .param("tenant", tenantId)
                .param("user", ownerUserId)
                .query(JdbcLedgerRepository::position)
                .list();
        for (JdbcLedgerRepository.PositionRow position : positions) {
            Holding replayed = replay(position);
            facts.add(new PositionFacts(
                    position.positionId(),
                    position.botId(),
                    position.quantity(),
                    position.costBasis(),
                    replayed.quantity(),
                    replayed.costBasis()));
        }
        return facts;
    }

    private Holding replay(JdbcLedgerRepository.PositionRow position) {
        Holding holding = Holding.empty();
        List<Object[]> fills = jdbc.sql("""
                        select o.kind, o.side, f.filled_quantity, f.gross_amount
                        from fill f join paper_order o on o.order_id = f.order_id
                        where f.bot_id = :bot and f.chain = :chain and f.token_mint = :mint and f.mode = 'PAPER'
                        order by f.filled_at, f.fill_seq
                        """)
                .param("bot", position.botId())
                .param("chain", position.chain())
                .param("mint", position.tokenMint())
                .query((rs, row) -> new Object[] {
                    rs.getString("kind"),
                    rs.getString("side"),
                    rs.getBigDecimal("filled_quantity"),
                    rs.getBigDecimal("gross_amount")
                })
                .list();
        for (Object[] fill : fills) {
            BigDecimal quantity = (BigDecimal) fill[2];
            BigDecimal gross = (BigDecimal) fill[3];
            if ("CLOSE".equals(fill[0])) {
                holding = PositionAccounting.close(holding, gross).after();
            } else if ("BUY".equals(fill[1])) {
                holding = PositionAccounting.buy(holding, quantity, gross).after();
            } else if (quantity.compareTo(holding.quantity()) <= 0) {
                holding = PositionAccounting.sell(holding, quantity, gross).after();
            } else {
                // A recorded sell larger than the replayed position is itself the divergence: keep the replay honest.
                return new Holding(holding.quantity().subtract(quantity), holding.costBasis());
            }
        }
        return holding;
    }

    private List<SummaryFacts> summaries(UUID tenantId, UUID ownerUserId) {
        return jdbc.sql("""
                        select d.bot_id, d.chain, d.token_mint, d.utc_day, d.realized, d.fees,
                               coalesce(x.realized, 0) as direct_realized, coalesce(x.fees, 0) as direct_fees
                        from pnl_daily d
                        left join lateral (
                          select sum(coalesce(f.realized, 0)) as realized, sum(""" + LedgerReadModelImpl.NET_FEES + """
                        ) as fees
                          from fill f
                          where f.bot_id = d.bot_id and f.chain = d.chain and f.token_mint = d.token_mint
                            and f.filled_at >= (d.utc_day::timestamp at time zone 'UTC')
                            and f.filled_at < ((d.utc_day + 1)::timestamp at time zone 'UTC')) x on true
                        where d.tenant_id = :tenant and d.owner_user_id = :user and d.mode = 'PAPER'
                        order by d.bot_id, d.utc_day, d.chain, d.token_mint
                        """)
                .param("tenant", tenantId)
                .param("user", ownerUserId)
                .query((rs, row) -> new SummaryFacts(
                        rs.getObject("bot_id", UUID.class),
                        rs.getString("chain"),
                        rs.getString("token_mint"),
                        rs.getObject("utc_day", LocalDate.class),
                        rs.getBigDecimal("realized"),
                        rs.getBigDecimal("fees"),
                        rs.getBigDecimal("direct_realized"),
                        rs.getBigDecimal("direct_fees")))
                .list();
    }
}
