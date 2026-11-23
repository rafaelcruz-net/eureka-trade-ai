package com.memetrade.ledger.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 261121-paper-real-unico U2 (C12, ENT-203): one REAL ledger row delivered by
 * {@link RealLedger#streamInNetworkOrder}, already in network order, with its key and where its slot comes from.
 * Lamports carry the sign of the ledger (SWAP_FILL_IN, NETWORK_FEE and RENT negative; SWAP_FILL_OUT positive); the
 * mint and the base units only on the rows of a token. Never a PAPER row.
 */
public record NetworkOrderedEntry(
        UUID entryId,
        UUID botWalletId,
        RealEntryKind kind,
        long lamports,
        Optional<String> mint,
        Optional<Long> tokenBaseUnits,
        Optional<String> txSignature,
        String reference,
        Instant createdAt,
        NetworkOrderKey key,
        SlotStatus slotStatus) {

    public NetworkOrderedEntry {
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(botWalletId, "botWalletId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(mint, "mint");
        Objects.requireNonNull(tokenBaseUnits, "tokenBaseUnits");
        Objects.requireNonNull(txSignature, "txSignature");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(slotStatus, "slotStatus");
    }

    /** The idempotency key of the row, the last tie-break of its key. */
    public String idempotencyKey() {
        return key.idempotencyKey();
    }
}
