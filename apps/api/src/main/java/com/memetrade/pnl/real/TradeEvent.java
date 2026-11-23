package com.memetrade.pnl.real;

import com.memetrade.ledger.api.NetworkOrderKey;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 261121-paper-real-unico U2 (ENT-207, F1 step 5, BR7.6): one event of the calculation, assembled from the trading rows
 * of one bot wallet with the same reference and signature, and the attribution of its order. It is applied in network
 * order, by the key of its main row. Lamports are positive amounts here (what was paid, received, charged).
 *
 * @param mint the token; empty only for a fee of a conditional without its fill
 * @param swapLamports paid by a buy (the SOL leg) or received by a sale; 0 for a fee alone
 * @param rentLamports the RENT of the reference (in the cost of a buy, BR2.1; a fee otherwise, BR6.6)
 * @param feeLamports the network fee: the NETWORK_FEE rows, or the fee of the order in the mode Assinada (BR6.3)
 * @param order the order of {@code order:<id>}; empty for a conditional and for an order gone
 * @param orderMissing true when the reference names an order that is gone (BR5.4)
 * @param at the one instant of the event (BR7.6)
 * @param key the key of the main row; empty for a virtual attempt of the mode Assinada
 */
public record TradeEvent(
        Type type,
        String reference,
        Optional<UUID> botWalletId,
        Optional<String> mint,
        long tokenBaseUnits,
        long swapLamports,
        long rentLamports,
        long feeLamports,
        Optional<OrderAttribution> order,
        boolean orderMissing,
        Instant at,
        Optional<NetworkOrderKey> key) {

    /** BUY, the sale of a bot, a sale without bot (liquidation or conditional) or a fee alone (a failed order). */
    public enum Type {
        BUY,
        OWN_SALE,
        SALE_WITHOUT_BOT,
        FEE_ONLY
    }

    public TradeEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(botWalletId, "botWalletId");
        Objects.requireNonNull(mint, "mint");
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(key, "key");
        if (tokenBaseUnits < 0 || swapLamports < 0 || rentLamports < 0 || feeLamports < 0) {
            throw new IllegalArgumentException("the amounts of an event are never negative");
        }
        if (type != Type.FEE_ONLY && (mint.isEmpty() || tokenBaseUnits < 1)) {
            throw new IllegalArgumentException("a buy or a sale moves at least 1 base unit of its mint");
        }
    }

    /** A conditional sells without an order ({@code conditional:<id>}). */
    public boolean conditional() {
        return reference.startsWith(RealPnlEngine.CONDITIONAL);
    }
}
