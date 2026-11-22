package com.memetrade.pnl.modeview;

import java.util.Objects;

/**
 * 261121-paper-real-unico U1 (ENT-008, BR5.1; ADR-003): the P&L by mode: the period and the two blocks side by side,
 * each its own market, and nothing else. There is no field of a total outside the blocks; the statement is generic, so
 * it never even sees a value of either mode.
 *
 * @param period {@code ALL} in the U1 (the U3 adds {@code RANGE})
 */
public record ModePnlStatement<P, R>(String period, BlockResult<P> paper, BlockResult<R> real) {

    public static final String ALL = "ALL";

    public ModePnlStatement {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(paper, "paper");
        Objects.requireNonNull(real, "real");
    }
}
