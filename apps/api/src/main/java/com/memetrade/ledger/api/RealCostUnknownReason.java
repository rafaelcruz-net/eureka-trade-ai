package com.memetrade.ledger.api;

/** 261121-paper-real-unico U2 (F-3, C11): why the cost of a REAL position is not known. Never read as 0. */
public enum RealCostUnknownReason {
    /** The read gave only the quantity (positions and view of the ledger, BR2.5): the cost was not asked for. */
    NOT_READ,
    /** The read with attribution passed its budget ({@code 57014} or the deadline at a row). */
    TIMEOUT,
    /** The read with attribution failed for another technical reason. */
    ERROR,
    /** An order of the wallet names a bot that is not a REAL bot of the person (BR3.4). */
    UNKNOWN_BOT,
    /** The read with attribution saw another set of rows than the view read before it (performance-design.md). */
    INCONSISTENT_READ
}
