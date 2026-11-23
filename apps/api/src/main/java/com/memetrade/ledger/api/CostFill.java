package com.memetrade.ledger.api;

import java.util.Objects;
import java.util.Optional;

/**
 * 261121-paper-real-unico U2 (C11, BR2.8 item 2): one swap fill on the cost path of {@link RealCostMethod}, already
 * attributed and in network order.
 *
 * <ul>
 *   <li>{@code SWAP_FILL_IN} (a buy): {@code holder} is its one holder (BR3.1); {@code lamports} is negative, the
 *       lamports paid for the token: the SOL leg plus the RENT of the account the buy created, never the network fee
 *       (BR2.1).
 *   <li>{@code SWAP_FILL_OUT} (a sale): {@code holder} is the seller of an own sale (BR3.2, BR4.4); empty for a sale
 *       without bot (a liquidation or a conditional, BR4.3), which is shared among the holders by quantity;
 *       {@code lamports} is the positive amount received.
 * </ul>
 */
public record CostFill(
        NetworkOrderKey key,
        RealEntryKind kind,
        String mint,
        long tokenBaseUnits,
        long lamports,
        Optional<CostHolder> holder) {

    public CostFill {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(mint, "mint");
        Objects.requireNonNull(holder, "holder");
        if (tokenBaseUnits < 1) {
            throw new IllegalArgumentException("a swap fill moves at least 1 base unit");
        }
        switch (kind) {
            case SWAP_FILL_IN -> {
                if (lamports > 0 || holder.isEmpty()) {
                    throw new IllegalArgumentException("a buy pays lamports (negative) and has its holder");
                }
            }
            case SWAP_FILL_OUT -> {
                if (lamports < 0) {
                    throw new IllegalArgumentException("a sale receives lamports (never negative)");
                }
            }
            default -> throw new IllegalArgumentException("only a swap fill is on the cost path");
        }
    }
}
