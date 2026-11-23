package com.memetrade.ledger.api;

/**
 * 261121-paper-real-unico U2 (C12, C14, BR1.5): where the slot of a REAL ledger row comes from, computed at read time
 * (no column, no old row filled).
 */
public enum SlotStatus {
    /** {@code real_entry_slot} has the signature of the row. */
    RECORDED,
    /** The row has a signature without a recorded slot (every row before the migration 0023); it inherits. */
    SLOT_MISSING,
    /** The row has no signature (RESERVE, RESERVE_RELEASE, COMPENSATION and the entries of a withdrawal); it inherits. */
    NO_NETWORK_TX
}
