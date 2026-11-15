package com.memetrade.ledger.api;

import java.util.Objects;
import java.util.UUID;

/**
 * One REAL ledger entry to record (M2). {@code reference} names what originated it ({@code dep:<signature>},
 * {@code wd:<id>}, {@code order:<id>}); {@code idempotencyKey} makes it recorded at most once (BR3.1, BR4.7).
 *
 * <p>261113-trading-real-bot U3 (M2 {@code positions}): a swap fill carries the {@code mint} and the
 * {@code tokenBaseUnits} it moved (both required there, absent elsewhere). Sign convention of the fills, used by the
 * positions: {@code SWAP_FILL_IN} brings the token in (lamports leave, negative), {@code SWAP_FILL_OUT} takes it out
 * (lamports come back, positive); the quantity is always positive.
 */
public record RealEntry(
        UUID tenantId,
        UUID botWalletId,
        RealEntryKind kind,
        long lamports,
        String txSignature,
        String reference,
        String idempotencyKey,
        String mint,
        Long tokenBaseUnits) {

    public RealEntry {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(botWalletId, "botWalletId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    }

    /** An entry without a token (every entry of the U1). */
    public RealEntry(
            UUID tenantId,
            UUID botWalletId,
            RealEntryKind kind,
            long lamports,
            String txSignature,
            String reference,
            String idempotencyKey) {
        this(tenantId, botWalletId, kind, lamports, txSignature, reference, idempotencyKey, null, null);
    }

    /** A swap fill of an order (U4): the SOL leg in lamports and the token leg in base units. */
    public static RealEntry swapFill(
            UUID tenantId,
            UUID botWalletId,
            RealEntryKind kind,
            long lamports,
            String mint,
            long tokenBaseUnits,
            String reference,
            String idempotencyKey) {
        return new RealEntry(
                tenantId, botWalletId, kind, lamports, null, reference, idempotencyKey, mint, tokenBaseUnits);
    }
}
