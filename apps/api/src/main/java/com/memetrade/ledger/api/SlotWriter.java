package com.memetrade.ledger.api;

/**
 * 261121-paper-real-unico U2 (C13, C14, NFR2.13): the three confirmations that record the slot of a REAL signature. The
 * name is the {@code writer} label of {@code ledger_real_slot_conflicts_total} and the {@code writer} detail of the
 * audit event REAL_SLOT_CONFLICT.
 */
public enum SlotWriter {
    /** The confirmation of a REAL order, CONFIRMED or FAILED with a transaction (realexecution). */
    ORDER_CONFIRMATION,
    /** The fill of a conditional, confirmed on the network (realexits FillPosting). */
    CONDITIONAL_FILL,
    /** The confirmation of a deposit, of a withdrawal and of its fee (botwallet). */
    WALLET_CONFIRMATION
}
