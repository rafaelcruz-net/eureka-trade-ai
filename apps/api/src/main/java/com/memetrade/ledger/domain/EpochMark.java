package com.memetrade.ledger.domain;

import com.memetrade.ledger.api.NetworkOrderKey;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 261121-paper-real-unico U2a, Etapa 14b (decision of the founder of 2026-11-22): one mark of zero of a mint of a bot
 * wallet, a row of {@code real_position_epoch}. It holds a fact derived from the quantity-only path (BR2.5), never a
 * cost: in network order (BR1.1), the quantity of the wallet in {@code mint} was zero right after the row of key
 * {@code zero}, and {@code fillsThrough} swap fills of the mint are at or before it. The read of the view starts after
 * it; every row of the mint after it was created at or after {@code windowFrom}.
 *
 * @param zero the key of the row after which the quantity was zero; empty for a full-read mark (no zero is known: every
 *     fill of the mint is read, from {@code windowFrom}, its first one)
 * @param fillsThrough the swap fills of the mint at or before {@code zero}; 0 without a zero
 * @param windowFrom no row of the mint after the zero was created before this instant
 */
public record EpochMark(
        String mint, Optional<NetworkOrderKey> zero, long fillsThrough, Instant windowFrom, EpochMarkReason reason) {

    public EpochMark {
        Objects.requireNonNull(mint, "mint");
        Objects.requireNonNull(zero, "zero");
        Objects.requireNonNull(windowFrom, "windowFrom");
        Objects.requireNonNull(reason, "reason");
        if (mint.isBlank()) {
            throw new IllegalArgumentException("a mark names its mint");
        }
        if (fillsThrough < 0) {
            throw new IllegalArgumentException("a count of fills is never negative");
        }
        if (zero.isEmpty() && fillsThrough != 0) {
            throw new IllegalArgumentException("a mark without a zero counts no fill before it");
        }
    }

    /** The same zero (compared like the network order) and the same count of fills. */
    public boolean sameZeroAs(Optional<NetworkOrderKey> other, long otherFills) {
        if (zero.isPresent() != other.isPresent() || fillsThrough != otherFills) {
            return false;
        }
        return zero.isEmpty() || zero.get().compareTo(other.get()) == 0;
    }
}
