package com.memetrade.bots.domain;

/**
 * 261121-paper-real-unico U4 (NFR2.11, NFR2.17): what the cut did to a SELL of the REAL cycle, the label
 * {@code outcome} of {@code real_cycle_sell_capped_total}: CAPPED (sold less than asked), NO_OWN_QUANTITY (nothing of
 * its own available: the sale was dropped, or a mint left the request) and STALE_SNAPSHOT (the mark of the bot changed
 * between the read and the lock).
 */
public enum SellCapOutcome {
    CAPPED,
    NO_OWN_QUANTITY,
    STALE_SNAPSHOT
}
