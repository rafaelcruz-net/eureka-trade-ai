package com.memetrade.pnl.api;

/**
 * 261121-paper-real-unico U1, C10 (NFR4.4): a read of the REAL block failed, or the person has more settled orders than
 * the P&L reads ({@code TOO_MANY_ORDERS}); the REAL block of C4 becomes UNAVAILABLE and no partial number leaves.
 *
 * <p>261121-paper-real-unico U2 (BR8.7, F8, NFR1.6): the closed causes of the label {@code cause} of
 * {@code pnl.mode_block.unavailable}: {@link #TIMEOUT} (any deadline of the block or of the attribution: {@code 57014}
 * or the deadline at a row), {@link #RECONCILIATION_MISMATCH} (a difference with the ledger or an UNKNOWN_BOT, audited)
 * and {@link #TOO_MANY_ORDERS}; any other label is a technical error (the simple name of the class of the failure, for
 * the log only).
 */
public class RealPnlUnavailable extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String TOO_MANY_ORDERS = "TOO_MANY_ORDERS";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String RECONCILIATION_MISMATCH = "RECONCILIATION_MISMATCH";

    private final String label;

    public RealPnlUnavailable(String label) {
        super("The REAL P&L could not be calculated: " + label);
        this.label = label;
    }

    public RealPnlUnavailable(String label, Throwable cause) {
        super("The REAL P&L could not be calculated: " + label, cause);
        this.label = label;
    }

    /** The cause of the log, a closed value or the simple name of the class of the failure; never data. */
    public String causeLabel() {
        return label;
    }
}
