package com.memetrade.pnl.api;

/**
 * 261121-paper-real-unico U3, C10 {@code botLines} (BR4.3; performance-design.md "Busca da âncora"): the cursor of C5 is
 * malformed, of another bot or version, or its anchor is not among the lines of the bot any more (a prorated line that
 * changed between two pages). The route answers {@code 400 VALIDATION_FAILED} on the field {@code cursor}; the text of
 * the cursor is never part of the message.
 */
public class RealPnlLineCursorRejected extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RealPnlLineCursorRejected(String reason) {
        super(reason);
    }
}
