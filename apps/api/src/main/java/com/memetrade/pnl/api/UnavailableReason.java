package com.memetrade.pnl.api;

/**
 * 261121-paper-real-unico U4 (C15, ENT-404 split by the alignment A11): why the own holdings of a bot could not be
 * read. {@code BUDGET_EXCEEDED}: the read passed its budget (a statement cancelled by the server, the deadline at a row,
 * or the whole read with the wait for a connection); {@code READ_FAILED}: any other failure of the read (the database,
 * the exact arithmetic, an inconsistent epoch); {@code BOT_NOT_OWNED}: the bot is not a REAL bot of the person in the
 * tenant. The label {@code reason} of {@code real_own_holdings_unavailable_total}.
 */
public enum UnavailableReason {
    BUDGET_EXCEEDED,
    READ_FAILED,
    BOT_NOT_OWNED
}
