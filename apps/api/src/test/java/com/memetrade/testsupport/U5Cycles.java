package com.memetrade.testsupport;

import com.memetrade.marketdata.api.TokenId;
import com.memetrade.riskassessment.api.RiskBand;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Market scripts and row counts of the cycle tests: a momentum BUY signal, a flat market, an exit price; the decisions,
 * cycles, evaluations of U6 and orders of U7 counted from the database (read only, to prove what was or was not
 * written).
 */
public final class U5Cycles {

    private U5Cycles() {}

    /** D1 at {@code slot}: volume +20%, price 1,00 to 1,02, reference 1,02 now, deep liquidity, band LOW. */
    public static void buySignal(U5MarketData market, U5Assessments assessments, TokenId token, Instant slot) {
        market.window(token, slot, new String[] {"1.00", "1.01", "1.01", "1.02"}, new String[] {
            "1000000", "1000000", "1000000", "1200000"
        });
        market.values().set(token, "1.02", "100000000", 6);
        assessments.band(token, RiskBand.LOW);
    }

    /** A flat market at {@code price}: no buy signal; with a position, the exits decide. */
    public static void flat(
            U5MarketData market,
            U5Assessments assessments,
            TokenId token,
            Instant slot,
            String price,
            String liquidity) {
        market.window(token, slot, new String[] {price, price, price, price}, new String[] {
            "1000000", "1000000", "1000000", "1000000"
        });
        market.values().set(token, price, liquidity, 6);
        assessments.band(token, RiskBand.LOW);
    }

    public static long decisions(JdbcClient jdbc, java.util.UUID botId) {
        return jdbc.sql("select count(*) from decision where bot_id = :bot")
                .param("bot", botId)
                .query(Long.class)
                .single();
    }

    public static long cycles(JdbcClient jdbc, java.util.UUID botId) {
        return jdbc.sql("select count(*) from bot_cycle where bot_id = :bot")
                .param("bot", botId)
                .query(Long.class)
                .single();
    }

    public static long evaluations(JdbcClient jdbc, java.util.UUID botId) {
        return jdbc.sql("select count(*) from order_evaluation where bot_id = :bot")
                .param("bot", botId)
                .query(Long.class)
                .single();
    }

    public static long orders(JdbcClient jdbc, java.util.UUID botId) {
        return jdbc.sql("select count(*) from paper_order where bot_id = :bot")
                .param("bot", botId)
                .query(Long.class)
                .single();
    }

    public static String cycleStatus(JdbcClient jdbc, java.util.UUID botId, Instant slot) {
        return jdbc.sql("select status || coalesce(':' || skip_reason, '') || coalesce(':' || outcome, '')"
                        + " from bot_cycle where bot_id = :bot and slot_start = :slot")
                .param("bot", botId)
                .param("slot", java.sql.Timestamp.from(slot))
                .query(String.class)
                .single();
    }
}
