package com.memetrade.pnl.modeview;

import com.memetrade.pnl.api.PnlMarket;
import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * 261121-paper-real-unico U1 (performance-design.md "Execução de cada bloco"): the own read-only transaction of one
 * block of the P&L by mode, opened on the thread of the pool, and the statement timeout inside it. PAPER reads in
 * REPEATABLE READ (lines and total from the same snapshot, BR5.10); REAL in READ COMMITTED.
 */
public interface BlockReads {

    /** Runs {@code body} in a new read-only transaction of the isolation of {@code market}. */
    <T> T inTransaction(PnlMarket market, Callable<T> body) throws Exception;

    /** Inside the transaction: the database cancels a statement that would go on after {@code left}. */
    void statementTimeout(Duration left);
}
