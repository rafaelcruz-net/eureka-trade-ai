package com.memetrade.ledger.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 261113-trading-real-bot U7 (M2 {@code streamEntries}, BR7): one REAL ledger entry as the fiscal export reads it.
 * Lamports and base units are {@code long}; the mint and the quantity only on the entries of a token.
 */
public record RealEntryRecord(
        RealEntryKind kind,
        long lamports,
        Optional<String> mint,
        Optional<Long> tokenBaseUnits,
        Optional<String> txSignature,
        String reference,
        Instant createdAt) {

    public RealEntryRecord {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(mint, "mint");
        Objects.requireNonNull(tokenBaseUnits, "tokenBaseUnits");
        Objects.requireNonNull(txSignature, "txSignature");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
