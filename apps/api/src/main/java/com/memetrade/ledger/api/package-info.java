/**
 * Public interface of the PaperTradingLedger module (U7): {@link com.memetrade.ledger.api.PaperExecution} (C15) for
 * U5, {@link com.memetrade.ledger.api.LedgerReadModel} and the event {@link com.memetrade.ledger.api.FillRecorded}
 * for the {@code pnl} module, and {@link com.memetrade.ledger.api.LedgerChangeMarks} (C18). No other module reads the
 * tables {@code paper_account}, {@code paper_order}, {@code fill}, {@code ledger_entry}, {@code position},
 * {@code pnl_daily} or {@code simulation_policy}.
 */
package com.memetrade.ledger.api;
