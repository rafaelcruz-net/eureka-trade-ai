package com.memetrade.pnl.modeview;

import com.memetrade.pnl.api.PnlWindow;
import java.util.Objects;

/**
 * 261121-paper-real-unico U1 (ENT-008, BR5.1; ADR-003): the P&L by mode: the period and the two blocks side by side,
 * each its own market, and nothing else. There is no field of a total outside the blocks; the statement is generic, so
 * it never even sees a value of either mode.
 *
 * <p>261121-paper-real-unico U3 (BR2.4, C6): the period is the window read ({@link PnlWindow.All} or
 * {@link PnlWindow.Range}); the same statement carries the two sides of C6.
 */
public record ModePnlStatement<P, R>(PnlWindow period, BlockResult<P> paper, BlockResult<R> real) {

    public static final String ALL = "ALL";
    public static final String RANGE = "RANGE";

    public ModePnlStatement {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(paper, "paper");
        Objects.requireNonNull(real, "real");
    }
}
