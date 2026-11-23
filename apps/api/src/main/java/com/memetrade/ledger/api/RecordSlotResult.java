package com.memetrade.ledger.api;

/**
 * 261121-paper-real-unico U2 (C13, BR1.4): what {@link RealLedger#recordSlot} did with the slot of a signature. None of
 * the three ever fails the confirmation that asked for it.
 */
public enum RecordSlotResult {
    /** The signature had no slot: this one is recorded. */
    RECORDED,
    /** The signature already had this very slot: nothing changes (idempotent). */
    ALREADY_RECORDED,
    /** The signature already had another slot: the first one stays, this one is refused, audited and counted. */
    CONFLICT_REFUSED
}
