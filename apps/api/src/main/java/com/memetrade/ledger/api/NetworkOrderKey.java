package com.memetrade.ledger.api;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/**
 * 261121-paper-real-unico U2 (C12, ENT-201, BR1.1 to BR1.3, decision F-1): the one network order key of the REAL rows
 * of a bot wallet, the same for every reader (BR1.6). It is computed by the provider of the read (the SQL of
 * {@code streamInNetworkOrder}); this type compares two keys exactly like that SQL orders them.
 *
 * <p>Total order: the effective slot; in the same slot the rows with a recorded slot before the inherited ones; among
 * the recorded rows the signature compared byte by byte (BR1.2, {@code COLLATE "C"}); then {@code createdAt},
 * {@code reference} by bytes, {@code kindRank} and {@code idempotencyKey} by bytes. The random entry id is never part of
 * it: the ledger has no sequence column, and the idempotency key is unique and made of business identifiers only.
 *
 * @param effectiveSlot the recorded slot, or the inherited one (0 before the first recorded row of the wallet)
 * @param inherited true when the row has no recorded slot
 * @param txSignature the signature of the row; present on every recorded row
 * @param kindRank SWAP_FILL_IN and SWAP_FILL_OUT 0, NETWORK_FEE 1, RENT 2, the others 3 ({@link #kindRank})
 */
public record NetworkOrderKey(
        long effectiveSlot,
        boolean inherited,
        Optional<String> txSignature,
        Instant createdAt,
        String reference,
        int kindRank,
        String idempotencyKey)
        implements Comparable<NetworkOrderKey> {

    private static final Comparator<String> BYTES =
            (a, b) -> Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));

    public NetworkOrderKey {
        Objects.requireNonNull(txSignature, "txSignature");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (effectiveSlot < 0) {
            throw new IllegalArgumentException("a slot is never negative");
        }
        if (!inherited && txSignature.isEmpty()) {
            throw new IllegalArgumentException("a row with a recorded slot has a signature");
        }
        if (kindRank < 0 || kindRank > 3) {
            throw new IllegalArgumentException("the rank of a kind is 0 to 3");
        }
    }

    /** The rank of a kind inside one event, as the SQL computes {@code kind_rank}. */
    public static int kindRank(RealEntryKind kind) {
        return switch (kind) {
            case SWAP_FILL_IN, SWAP_FILL_OUT -> 0;
            case NETWORK_FEE -> 1;
            case RENT -> 2;
            case DEPOSIT, WITHDRAWAL, COMPENSATION, RESERVE, RESERVE_RELEASE -> 3;
        };
    }

    @Override
    public int compareTo(NetworkOrderKey other) {
        int order = Long.compare(effectiveSlot, other.effectiveSlot);
        if (order != 0) {
            return order;
        }
        order = Boolean.compare(inherited, other.inherited);
        if (order != 0) {
            return order;
        }
        if (!inherited) {
            order = BYTES.compare(txSignature.orElseThrow(), other.txSignature.orElseThrow());
            if (order != 0) {
                return order;
            }
        }
        order = createdAt.compareTo(other.createdAt);
        if (order != 0) {
            return order;
        }
        order = BYTES.compare(reference, other.reference);
        if (order != 0) {
            return order;
        }
        order = Integer.compare(kindRank, other.kindRank);
        if (order != 0) {
            return order;
        }
        return BYTES.compare(idempotencyKey, other.idempotencyKey);
    }
}
