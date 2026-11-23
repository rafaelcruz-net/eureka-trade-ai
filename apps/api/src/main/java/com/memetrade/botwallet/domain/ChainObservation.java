package com.memetrade.botwallet.domain;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * What one RPC provider says about a withdrawal signature, always in commitment finalized (reliability-design.md): the
 * transaction finalized with its real fee, not found at a finalized height, or no answer.
 *
 * @param slot 261121-paper-real-unico U2 (BR1.4): the slot of a finalized signature, from the same getSignatureStatuses
 *     answer; empty otherwise or when the answer did not carry it
 */
public record ChainObservation(
        Status status, String txSignature, long feeLamports, long finalizedHeight, OptionalLong slot) {

    public enum Status {
        FINALIZED,
        NOT_FOUND,
        UNAVAILABLE
    }

    public ChainObservation {
        Objects.requireNonNull(slot, "slot");
    }

    public static ChainObservation finalized(String txSignature, long feeLamports) {
        return finalized(txSignature, feeLamports, OptionalLong.empty());
    }

    public static ChainObservation finalized(String txSignature, long feeLamports, OptionalLong slot) {
        return new ChainObservation(Status.FINALIZED, txSignature, feeLamports, 0, slot);
    }

    public static ChainObservation notFound(long finalizedHeight) {
        return new ChainObservation(Status.NOT_FOUND, null, 0, finalizedHeight, OptionalLong.empty());
    }

    public static ChainObservation unavailable() {
        return new ChainObservation(Status.UNAVAILABLE, null, 0, 0, OptionalLong.empty());
    }
}
