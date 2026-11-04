package com.memetrade.ledger.db;

import static com.memetrade.testsupport.LedgerRows.sqlState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.memetrade.identity.api.Principal;
import com.memetrade.testsupport.AbstractU7IT;
import com.memetrade.testsupport.LedgerRows;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Passo 4 (written before the migration): the privilege matrix of security-design.md (NFR6.2), one row per test, with
 * one allowed and one refused operation of the APPLICATION login, plus the checks, transitions and unique keys of the
 * migration 0008 (BR3.1, BR3.4, BR4.1, BR9.2, NFR5.3, NFR4.2).
 */
class LedgerPrivilegesIT extends AbstractU7IT {

    private static final String DENIED = "42501";
    private static final String CHECK = "23514";
    private static final String UNIQUE = "23505";

    private JdbcClient app;
    private LedgerRows rows;
    private Principal person;
    private UUID bot;

    @BeforeEach
    void seed() {
        app = appRole();
        rows = new LedgerRows(app);
        person = newPerson();
        bot = rows.account(person, "OPEN");
    }

    @Test
    void ledgerEntryAcceptsInsertAndSelectAndRefusesUpdateDeleteAndTruncate() {
        UUID entry = rows.entry(person, bot, "FUNDING", "1000.000000", "fund-" + UUID.randomUUID(), null);

        assertThat(app.sql("select amount from ledger_entry where entry_id = :e")
                        .param("e", entry)
                        .query(BigDecimal.class)
                        .single())
                .isEqualByComparingTo("1000");
        assertThat(sqlState(fails("update ledger_entry set amount = 1 where entry_id = '" + entry + "'")))
                .isEqualTo(DENIED);
        assertThat(sqlState(fails("delete from ledger_entry where entry_id = '" + entry + "'")))
                .isEqualTo(DENIED);
        assertThat(sqlState(fails("truncate ledger_entry"))).isEqualTo(DENIED);
        // The trigger refuses even the owner of the schema (BR4.1: "de qualquer papel").
        assertThat(sqlState(catchThrowable(() -> connect(OWNER)
                        .sql("update ledger_entry set amount = 1 where entry_id = :e")
                        .param("e", entry)
                        .update())))
                .isEqualTo(DENIED);
    }

    @Test
    void fillAcceptsInsertAndRefusesUpdateAndDeleteForEveryLogin() {
        UUID order = rows.order(person, bot, "TRADE", "FILLED", null, "k-" + UUID.randomUUID());
        UUID fill = rows.fill(person, bot, order);

        assertThat(sqlState(fails("update fill set fees = 0 where fill_id = '" + fill + "'")))
                .isEqualTo(DENIED);
        assertThat(sqlState(fails("delete from fill where fill_id = '" + fill + "'")))
                .isEqualTo(DENIED);
        assertThat(sqlState(catchThrowable(() -> connect(OWNER)
                        .sql("delete from fill where fill_id = :f")
                        .param("f", fill)
                        .update())))
                .isEqualTo(DENIED);
    }

    @Test
    void paperOrderAcceptsOnlyClosePendingToFilledAndNoOtherColumnOrTransition() {
        UUID pending = rows.order(person, bot, "CLOSE", "CLOSE_PENDING", null, "close-" + UUID.randomUUID());
        UUID notFilled = rows.order(person, bot, "TRADE", "NOT_FILLED", "NO_LIQUIDITY", "k-" + UUID.randomUUID());

        assertThat(app.sql("update paper_order set status = 'FILLED' where order_id = :o")
                        .param("o", pending)
                        .update())
                .isEqualTo(1);
        assertThat(sqlState(fails("update paper_order set requested_quantity = 2 where order_id = '" + pending + "'")))
                .isEqualTo(DENIED);
        assertThat(sqlState(fails("update paper_order set status = 'FILLED', not_filled_reason = null"
                        + " where order_id = '" + notFilled + "'")))
                .isEqualTo(DENIED);
        assertThat(sqlState(fails("update paper_order set status = 'FILLED' where order_id = '" + notFilled + "'")))
                .isIn(CHECK, DENIED);
        assertThat(sqlState(
                        fails("update paper_order set status = 'CLOSE_PENDING' where order_id = '" + pending + "'")))
                .isEqualTo(CHECK);
        assertThat(sqlState(fails("delete from paper_order where order_id = '" + pending + "'")))
                .isEqualTo(DENIED);
    }

    @Test
    void positionAcceptsTheAccountingColumnsAndKeepsQuantityAndCostTogether() {
        UUID position = rows.position(person, bot, "100", "100.600000", "OPEN");

        assertThat(app.sql("update position set quantity = 50, cost_basis = 50.300000, version = version + 1"
                                + " where position_id = :p")
                        .param("p", position)
                        .update())
                .isEqualTo(1);
        assertThat(sqlState(fails("update position set token_mint = 'x' where position_id = '" + position + "'")))
                .isEqualTo(DENIED);
        assertThat(sqlState(fails("update position set cost_basis = 0 where position_id = '" + position + "'")))
                .isEqualTo(CHECK);
        assertThat(sqlState(fails("update position set quantity = -1 where position_id = '" + position + "'")))
                .isEqualTo(CHECK);
        assertThat(sqlState(fails("delete from position where position_id = '" + position + "'")))
                .isEqualTo(DENIED);
    }

    @Test
    void paperAccountGoesOnlyFromOpenToClosingAndToClosedWithoutOpenPositions() {
        UUID other = rows.account(person, "OPEN");
        UUID position = rows.position(person, other, "10", "10.000000", "OPEN");

        assertThat(updateStatus(bot, "CLOSING")).isEqualTo(1);
        assertThat(updateStatus(bot, "CLOSED")).isEqualTo(1);
        assertThat(sqlState(catchThrowable(() -> updateStatus(bot, "OPEN")))).isEqualTo(CHECK);

        assertThat(sqlState(catchThrowable(() -> updateStatus(other, "CLOSED"))))
                .isEqualTo(CHECK);
        assertThat(updateStatus(other, "CLOSING")).isEqualTo(1);
        assertThat(sqlState(catchThrowable(() -> updateStatus(other, "CLOSED"))))
                .isEqualTo(CHECK);
        app.sql("update position set quantity = 0, cost_basis = 0, status = 'CLOSED' where position_id = :p")
                .param("p", position)
                .update();
        assertThat(updateStatus(other, "CLOSED")).isEqualTo(1);

        assertThat(sqlState(fails("update paper_account set owner_user_id = tenant_id where bot_id = '" + bot + "'")))
                .isEqualTo(DENIED);
        assertThat(sqlState(fails("delete from paper_account where bot_id = '" + bot + "'")))
                .isEqualTo(DENIED);
    }

    @Test
    void equityPeakOnlyRises() {
        rows.peak(person, bot, "1000.000000");

        assertThat(app.sql("update equity_peak set peak_equity = 1017.996432, last_equity = null where bot_id = :b")
                        .param("b", bot)
                        .update())
                .isEqualTo(1);
        assertThat(sqlState(fails("update equity_peak set peak_equity = 999 where bot_id = '" + bot + "'")))
                .isEqualTo(CHECK);
        assertThat(sqlState(fails("delete from equity_peak where bot_id = '" + bot + "'")))
                .isEqualTo(DENIED);
    }

    @Test
    void theDailySummaryNeverLosesAFillAndChangesOnlyFeesWithoutANewFill() {
        LocalDate day = LocalDate.parse("2026-10-28");
        rows.daily(person, bot, day, "0.000000", "0.301800", 1);
        String where = " where bot_id = '" + bot + "'";

        assertThat(app.sql("update pnl_daily set realized = realized + 48.425, fees = fees + 0.447075,"
                                + " fill_count = fill_count + 1" + where)
                        .update())
                .isEqualTo(1);
        assertThat(app.sql("update pnl_daily set fees = fees - 0.3018" + where).update())
                .as("a FEE compensation changes only the fees")
                .isEqualTo(1);
        assertThat(sqlState(fails("update pnl_daily set fill_count = fill_count - 1" + where)))
                .isEqualTo(CHECK);
        assertThat(sqlState(fails("update pnl_daily set realized = realized + 1" + where)))
                .isEqualTo(CHECK);
        assertThat(sqlState(fails("update pnl_daily set fill_count = fill_count + 2" + where)))
                .isEqualTo(CHECK);
        assertThat(sqlState(fails("update pnl_daily set utc_day = utc_day + 1" + where)))
                .isEqualTo(DENIED);
        assertThat(sqlState(fails("delete from pnl_daily" + where))).isEqualTo(DENIED);
    }

    @Test
    void reconciliationChecksAreInsertedReadAndDeletedButNeverUpdated() {
        UUID check = rows.check(person);

        assertThat(sqlState(
                        fails("update reconciliation_check set status = 'DIVERGENT' where check_id = '" + check + "'")))
                .isEqualTo(DENIED);
        assertThat(app.sql("delete from reconciliation_check where check_id = :c")
                        .param("c", check)
                        .update())
                .isEqualTo(1);
    }

    @Test
    void thePolicyV1IsSeededAndOnlyReadable() {
        Map<String, Object> v1 = app.sql("select * from simulation_policy where version = 'v1'")
                .query()
                .singleRow();

        assertThat((BigDecimal) v1.get("spread_pct")).isEqualByComparingTo("0.5000");
        assertThat((BigDecimal) v1.get("impact_per_consumed_pct")).isEqualByComparingTo("1.0000");
        assertThat((BigDecimal) v1.get("fee_pct")).isEqualByComparingTo("0.3000");
        assertThat((BigDecimal) v1.get("max_liquidity_consumption_pct")).isEqualByComparingTo("10.0000");
        assertThat(((Number) v1.get("money_scale")).intValue()).isEqualTo(6);
        assertThat(((Number) v1.get("price_scale")).intValue()).isEqualTo(12);
        assertThat(v1.get("money_rounding")).isEqualTo("HALF_EVEN");
        assertThat(v1.get("quantity_rounding")).isEqualTo("DOWN");
        assertThat(sqlState(fails("update simulation_policy set fee_pct = 0"))).isEqualTo(DENIED);
        assertThat(sqlState(fails("insert into simulation_policy (version, spread_pct, impact_per_consumed_pct,"
                        + " fee_pct, max_liquidity_consumption_pct, money_scale, price_scale, money_rounding,"
                        + " quantity_rounding, activated_at) values ('v9', 0, 0, 0, 0, 6, 12, 'HALF_EVEN', 'DOWN',"
                        + " now())")))
                .isEqualTo(DENIED);
    }

    @Test
    void everyU7TableAcceptsOnlyPaperRows() {
        assertThat(sqlState(fails("insert into paper_account (bot_id, tenant_id, owner_user_id, mode, status,"
                        + " created_at) values ('" + UUID.randomUUID() + "', '" + person.tenantId() + "', '"
                        + person.userId() + "', 'REAL', 'OPEN', now())")))
                .isEqualTo(CHECK);
        assertThat(sqlState(fails("insert into ledger_entry (entry_id, tenant_id, owner_user_id, bot_id, mode, type,"
                        + " asset, amount, reference_id, created_at) values ('" + UUID.randomUUID() + "', '"
                        + person.tenantId() + "', '" + person.userId() + "', '" + bot + "', 'REAL', 'FUNDING',"
                        + " 'USDC', 1, 'x', now())")))
                .isEqualTo(CHECK);
        assertThat(sqlState(fails("insert into pnl_daily (tenant_id, owner_user_id, bot_id, mode, chain, token_mint,"
                        + " utc_day, realized, fees, fill_count, updated_at) values ('" + person.tenantId() + "', '"
                        + person.userId() + "', '" + bot + "', 'REAL', 'solana', 'm', current_date, 0, 0, 1,"
                        + " now())")))
                .isEqualTo(CHECK);
        // pg_catalog, not information_schema: the latter hides constraints of tables the login does not own.
        Long nonPaperColumns = jdbc.sql("""
                        select count(*) from pg_constraint
                        where contype = 'c' and pg_get_constraintdef(oid) like '%mode%''PAPER''%'
                          and conrelid::regclass::text in ('paper_account', 'paper_order', 'fill', 'ledger_entry',
                            '"position"', 'position', 'pnl_daily', 'equity_peak', 'reconciliation_check')
                        """).query(Long.class).single();
        assertThat(nonPaperColumns).isEqualTo(8L);
    }

    @Test
    void theIdempotencyKeyIsUniquePerTenantAndModeAndOrdersNeedKeyLimitsAndTheRightDecision() {
        String key = "k-" + UUID.randomUUID();
        rows.order(person, bot, "TRADE", "FILLED", null, key);
        Principal otherTenant = newPerson();
        UUID otherBot = rows.account(otherTenant, "OPEN");

        assertThat(sqlState(catchThrowable(() -> rows.order(person, bot, "TRADE", "FILLED", null, key))))
                .isEqualTo(UNIQUE);
        assertThatCode(() -> rows.order(otherTenant, otherBot, "TRADE", "FILLED", null, key))
                .doesNotThrowAnyException();
        assertThat(sqlState(fails("insert into paper_order (order_id, tenant_id, owner_user_id, bot_id, mode,"
                        + " decision_id, idempotency_key, content_digest, kind, side, chain, token_mint,"
                        + " requested_quantity, limits_version, status, created_at) values ('" + UUID.randomUUID()
                        + "', '" + person.tenantId() + "', '" + person.userId() + "', '" + bot + "', 'PAPER', null,"
                        + " 'k2', '" + "0".repeat(64) + "', 'TRADE', 'BUY', 'solana', 'm', 1, 1, 'FILLED', now())")))
                .as("TRADE without decision")
                .isEqualTo(CHECK);
        assertThat(sqlState(fails("insert into paper_order (order_id, tenant_id, owner_user_id, bot_id, mode,"
                        + " decision_id, idempotency_key, content_digest, kind, side, chain, token_mint,"
                        + " requested_quantity, limits_version, status, created_at) values ('" + UUID.randomUUID()
                        + "', '" + person.tenantId() + "', '" + person.userId() + "', '" + bot + "', 'PAPER', null,"
                        + " 'k3', '" + "0".repeat(64) + "', 'TRADE', 'BUY', 'solana', 'm', 1, null, 'FILLED',"
                        + " now())")))
                .as("no limits version")
                .isEqualTo("23502");
    }

    @Test
    void aBotHasAtMostOneFunding() {
        rows.entry(person, bot, "FUNDING", "1000.000000", "fund-a-" + UUID.randomUUID(), null);

        assertThat(sqlState(catchThrowable(
                        () -> rows.entry(person, bot, "FUNDING", "5.000000", "fund-b-" + UUID.randomUUID(), null))))
                .isEqualTo(UNIQUE);
    }

    @Test
    void aCompensationIsTheNegativeOfItsOriginalOnceAndNeverOfAnotherCompensation() {
        UUID fee = rows.entry(person, bot, "FEE", "-0.301800", UUID.randomUUID().toString(), null);

        assertThat(sqlState(
                        catchThrowable(() -> rows.entry(person, bot, "COMPENSATION", "0.300000", fee.toString(), fee))))
                .isEqualTo(CHECK);
        UUID compensation = rows.entry(person, bot, "COMPENSATION", "0.301800", fee.toString(), fee);
        assertThat(sqlState(
                        catchThrowable(() -> rows.entry(person, bot, "COMPENSATION", "0.301800", fee.toString(), fee))))
                .isEqualTo(UNIQUE);
        assertThat(sqlState(catchThrowable(() ->
                        rows.entry(person, bot, "COMPENSATION", "-0.301800", compensation.toString(), compensation))))
                .isEqualTo(CHECK);
        assertThat(sqlState(catchThrowable(() -> rows.entry(
                        person, bot, "FEE", "0.000000", UUID.randomUUID().toString(), null))))
                .as("an entry is never zero")
                .isEqualTo(CHECK);
    }

    private int updateStatus(UUID botId, String status) {
        return app.sql("update paper_account set status = :s where bot_id = :b")
                .param("s", status)
                .param("b", botId)
                .update();
    }

    private Throwable fails(String sql) {
        Throwable failure = catchThrowable(() -> app.sql(sql).update());
        assertThat(failure).as(sql).isNotNull();
        return failure;
    }
}
