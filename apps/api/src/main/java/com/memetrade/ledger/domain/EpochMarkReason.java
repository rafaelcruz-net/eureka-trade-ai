package com.memetrade.ledger.domain;

/**
 * 261121-paper-real-unico U2a, Etapa 14b: why a mark of zero ({@code real_position_epoch}) was written. The value is
 * the {@code reason} column of the mark; a mark is never updated nor deleted, a newer one supersedes it.
 */
public enum EpochMarkReason {
    /** A row brought the quantity of the wallet in the mint to zero (BR2.5). */
    ZERO,
    /**
     * A late confirmation landed before the latest mark in network order (the count of fills disagreed): the zero was
     * found again by a read of the whole history, or, with no zero left, the mint is read from its first fill.
     */
    OUT_OF_ORDER,
    /** A row was written before the window of the mark (a clock behind): the window starts earlier, the zero stays. */
    EARLY_ROW
}
