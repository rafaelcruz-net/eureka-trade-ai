package com.memetrade.pnl.application;

import com.memetrade.pnl.domain.PnlCalculator.Amount;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 261121-paper-real-unico U1 (ENT-009, BR5.2, BR5.4, BR6.1): the PAPER block of the P&L by mode, in exact US$: the
 * total of the mode computed from the PAPER ledger (never the sum of the lines), its parts, the lines of the PAPER bots
 * in order of creation and the fixed disclaimer. Only PAPER values; on the closed PAPER list of NEVER_SUM_MODES.
 *
 * @param total {@code realized + unrealized - fees}; unknown when the unrealized is unknown
 * @param empty no PAPER bot in the directory and no PAPER operation nor position
 */
public record PaperModeBlock(
        String disclaimer,
        boolean empty,
        Amount total,
        BigDecimal realized,
        Amount unrealized,
        Instant unrealizedAsOf,
        BigDecimal fees,
        List<PaperBotPnlLine> bots) {

    /** BR6.1, C-16: the fixed disclaimer of every simulated result. */
    public static final String DISCLAIMER = "O resultado simulado não garante o resultado real.";

    public PaperModeBlock {
        Objects.requireNonNull(disclaimer, "disclaimer");
        Objects.requireNonNull(total, "total");
        Objects.requireNonNull(realized, "realized");
        Objects.requireNonNull(unrealized, "unrealized");
        Objects.requireNonNull(unrealizedAsOf, "unrealizedAsOf");
        Objects.requireNonNull(fees, "fees");
        bots = List.copyOf(bots);
    }
}
