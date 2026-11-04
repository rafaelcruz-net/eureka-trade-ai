package com.memetrade.testsupport;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads what the ledger recorded, for the assertions of the U7 integration tests (application login, read only). */
public final class LedgerFacts {

    private final JdbcClient jdbc;

    public LedgerFacts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public BigDecimal balance(UUID botId) {
        return jdbc.sql("select coalesce(sum(amount), 0) from ledger_entry where bot_id = :b")
                .param("b", botId)
                .query(BigDecimal.class)
                .single();
    }

    /** "TYPE amount" of the entries of one order, in insertion order of type. */
    public List<String> entriesOf(UUID orderId) {
        return jdbc.sql("select type || ' ' || amount from ledger_entry where reference_id = :o order by type")
                .param("o", orderId.toString())
                .query(String.class)
                .list();
    }

    public long orders(UUID botId) {
        return jdbc.sql("select count(*) from paper_order where bot_id = :b")
                .param("b", botId)
                .query(Long.class)
                .single();
    }

    public long entries(UUID botId) {
        return jdbc.sql("select count(*) from ledger_entry where bot_id = :b")
                .param("b", botId)
                .query(Long.class)
                .single();
    }

    public long fills(UUID botId) {
        return jdbc.sql("select count(*) from fill where bot_id = :b")
                .param("b", botId)
                .query(Long.class)
                .single();
    }

    public Map<String, Object> order(UUID orderId) {
        return jdbc.sql("select * from paper_order where order_id = :o")
                .param("o", orderId)
                .query()
                .singleRow();
    }

    public Map<String, Object> position(UUID botId, String mint) {
        return jdbc.sql("select * from position where bot_id = :b and token_mint = :m")
                .param("b", botId)
                .param("m", mint)
                .query()
                .singleRow();
    }

    public List<Map<String, Object>> daily(UUID botId) {
        return jdbc.sql("select * from pnl_daily where bot_id = :b order by utc_day, token_mint")
                .param("b", botId)
                .query()
                .listOfRows();
    }

    public String accountStatus(UUID botId) {
        return jdbc.sql("select status from paper_account where bot_id = :b")
                .param("b", botId)
                .query(String.class)
                .single();
    }

    public Map<String, Object> peak(UUID botId) {
        return jdbc.sql("select * from equity_peak where bot_id = :b")
                .param("b", botId)
                .query()
                .singleRow();
    }

    public long events(String action, String targetRef) {
        return OutboxEvents.count(jdbc, action, targetRef);
    }

    public long eventsWithDetail(String action, String key, String value) {
        return jdbc.sql("select count(*) from audit_outbox where payload->>'action' = :a"
                        + " and payload->'details'->>'" + key + "' = :v")
                .param("a", action)
                .param("v", value)
                .query(Long.class)
                .single();
    }
}
