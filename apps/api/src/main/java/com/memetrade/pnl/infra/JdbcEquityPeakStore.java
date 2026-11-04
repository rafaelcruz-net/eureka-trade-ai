package com.memetrade.pnl.infra;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code equity_peak}, owned by the {@code pnl} module (logical-components.md). The peak rises only by a conditional
 * update (BR7.3) and the database refuses a lower one; the row is the LAST lock of BR2.8.
 */
@Repository
public class JdbcEquityPeakStore {

    private final JdbcClient jdbc;

    public JdbcEquityPeakStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Peak(BigDecimal peakEquity, Instant peakAt, Optional<BigDecimal> lastEquity, Instant lastEquityAt) {}

    /** F1: the initial peak equals the FUNDING amount. */
    public void start(UUID tenantId, UUID ownerUserId, UUID botId, BigDecimal amount, Instant at) {
        jdbc.sql("""
                        insert into equity_peak (bot_id, tenant_id, owner_user_id, mode, peak_equity, peak_at,
                          last_equity, last_equity_at)
                        values (:bot, :tenant, :user, 'PAPER', :amount, :at, :amount, :at)
                        """)
                .param("bot", botId)
                .param("tenant", tenantId)
                .param("user", ownerUserId)
                .param("amount", amount)
                .param("at", Timestamp.from(at))
                .update();
    }

    /**
     * Records the equity of the moment: {@code lastEquity} (empty when unknown) and the peak raised only when the new
     * equity is greater than the recorded one, in the same statement.
     */
    public void record(UUID botId, Optional<BigDecimal> equity, Instant at) {
        if (equity.isEmpty()) {
            jdbc.sql("update equity_peak set last_equity = null, last_equity_at = :at where bot_id = :bot")
                    .param("at", Timestamp.from(at))
                    .param("bot", botId)
                    .update();
            return;
        }
        jdbc.sql("""
                        update equity_peak
                        set last_equity = :equity, last_equity_at = :at,
                            peak_at = case when :equity > peak_equity then :at else peak_at end,
                            peak_equity = greatest(peak_equity, :equity)
                        where bot_id = :bot
                        """)
                .param("equity", equity.get())
                .param("at", Timestamp.from(at))
                .param("bot", botId)
                .update();
    }

    public Optional<Peak> find(UUID botId) {
        return jdbc.sql("select peak_equity, peak_at, last_equity, last_equity_at from equity_peak where bot_id = :bot")
                .param("bot", botId)
                .query((rs, row) -> new Peak(
                        rs.getBigDecimal("peak_equity"),
                        rs.getTimestamp("peak_at").toInstant(),
                        Optional.ofNullable(rs.getBigDecimal("last_equity")),
                        rs.getTimestamp("last_equity_at").toInstant()))
                .optional();
    }
}
