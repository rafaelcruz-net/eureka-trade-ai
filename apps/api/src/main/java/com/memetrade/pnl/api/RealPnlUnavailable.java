package com.memetrade.pnl.api;

/**
 * 261121-paper-real-unico U1, C10 (NFR4.4): a read of the REAL block failed, or the person has more settled orders than
 * the U1 reads ({@code TOO_MANY_ORDERS}); the REAL block of C4 becomes UNAVAILABLE and no partial number leaves.
 */
public class RealPnlUnavailable extends RuntimeException {

    public static final String TOO_MANY_ORDERS = "TOO_MANY_ORDERS";

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
