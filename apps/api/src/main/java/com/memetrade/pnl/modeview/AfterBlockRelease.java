package com.memetrade.pnl.modeview;

/**
 * 261121-paper-real-unico U2 (review R-01, decision of the founder of 2026-11-23; NFR1.16): a failure of a block that
 * still has work to do once the read transaction of the block has ended and its connection is back in the main pool
 * (the audit of a REAL reconciliation mismatch). {@link ModeBlockExecutor} calls {@link #afterTransaction()} on the
 * thread of the block, after the transaction and after the answer of the block was given, so that work never delays
 * the answer and never runs while the block holds a connection of the main pool.
 */
public interface AfterBlockRelease {

    /** Runs the deferred work; at most once, whatever the number of calls; never throws. */
    void afterTransaction();
}
