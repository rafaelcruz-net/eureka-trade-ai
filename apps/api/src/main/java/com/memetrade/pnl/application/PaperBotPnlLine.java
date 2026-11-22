package com.memetrade.pnl.application;

import com.memetrade.pnl.api.BotPnlStatus;
import com.memetrade.pnl.domain.PnlCalculator.Amount;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * 261121-paper-real-unico U1 (ENT-010, BR5.9): the P&L PAPER of one bot, the same calculation as
 * {@code GET /api/v1/pnl?botId=} since the beginning, in exact US$. On the closed PAPER list of NEVER_SUM_MODES.
 *
 * @param total {@code realized + unrealized - fees}; unknown when the unrealized is unknown
 */
public record PaperBotPnlLine(
        UUID botId,
        String name,
        BotPnlStatus status,
        BigDecimal realized,
        Amount unrealized,
        BigDecimal fees,
        Amount total) {

    public PaperBotPnlLine {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(realized, "realized");
        Objects.requireNonNull(unrealized, "unrealized");
        Objects.requireNonNull(fees, "fees");
        Objects.requireNonNull(total, "total");
    }
}
