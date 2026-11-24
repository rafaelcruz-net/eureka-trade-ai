package com.memetrade.pnl.api;

/**
 * 261121-paper-real-unico U3, C5 and C10 {@code botLines} (BR4.1, AC3.10.2): where a line of the P&L of a REAL bot comes
 * from. {@code BOT} is an order of the bot itself; the others are prorated parts of a sale that was not its own. The
 * order of the constants is the tie-break of the lines of one event.
 */
public enum RealPnlLineOrigin {
    BOT,
    LIQUIDATION,
    CONDITIONAL,
    SOLD_BY_OTHER_BOT
}
