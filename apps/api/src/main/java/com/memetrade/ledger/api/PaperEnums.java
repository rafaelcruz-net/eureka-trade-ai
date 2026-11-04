package com.memetrade.ledger.api;

/**
 * Closed value lists of the PAPER ledger (entities.md). The names are the same in Java, JSON and the database
 * (team.md, upper-case domain enums); every row of U7 is {@code PAPER}.
 */
public final class PaperEnums {

    private PaperEnums() {}

    /** The only mode of U7 (BR9.2): {@code CHECK mode = 'PAPER'} in every table. */
    public static final String MODE = "PAPER";

    public enum OrderSide {
        BUY,
        SELL
    }

    public enum OrderKind {
        TRADE,
        CLOSE
    }

    /** {@code PaperOrder.status}: an order is born final, except the close without a reliable price (state machine). */
    public enum OrderStatus {
        FILLED,
        PARTIALLY_FILLED,
        NOT_FILLED,
        CLOSE_PENDING
    }

    /** Why an order was recorded without a fill (C15 addition); a result, never an exception. */
    public enum NotFilledReason {
        PRICE_UNAVAILABLE,
        NO_LIQUIDITY,
        QUANTITY_BELOW_MINIMUM,
        NO_POSITION,
        ACCOUNT_CLOSING,
        INSUFFICIENT_BALANCE,
        TENANT_MISMATCH
    }

    public enum AccountStatus {
        OPEN,
        CLOSING,
        CLOSED
    }

    public enum PositionStatus {
        OPEN,
        CLOSE_PENDING,
        CLOSED
    }

    /** {@code LedgerEntry.type} (C5 addition: {@code FUNDING}). */
    public enum EntryType {
        FUNDING,
        TRADE_COST,
        TRADE_PROCEEDS,
        SLIPPAGE,
        FEE,
        CLOSE,
        COMPENSATION
    }
}
