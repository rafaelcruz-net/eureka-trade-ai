package com.memetrade.pnl.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 261121-paper-real-unico U1, C10 (ENT-011, BR5.5): the REAL block of the P&L by mode: one line per REAL bot of the
 * directory in order of creation, the line "Sem bot" (empty in the U1) and the total of the mode. In the U1 the orders
 * without a bot (liquidation, conditional) enter the total and no line, so the lines may not add up to the total
 * (RK-1); the screen shows a fixed note and never adds the lines.
 *
 * @param empty no REAL bot in the directory and no settled REAL order
 */
public record RealModePnl(
        List<RealBotPnlLine> lines, Optional<UnownedPnlLine> unowned, RealModeTotal total, boolean empty) {

    public RealModePnl {
        lines = List.copyOf(lines);
        Objects.requireNonNull(unowned, "unowned");
        Objects.requireNonNull(total, "total");
    }
}
