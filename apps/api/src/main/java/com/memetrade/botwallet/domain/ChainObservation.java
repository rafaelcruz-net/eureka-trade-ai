package com.memetrade.botwallet.domain;

/**
 * What one RPC provider says about a withdrawal signature, always in commitment finalized (reliability-design.md): the
 * transaction finalized with its real fee, not found at a finalized height, or no answer.
 */
public record ChainObservation(Status status, String txSignature, long feeLamports, long finalizedHeight) {

    public enum Status {
        FINALIZED,
        NOT_FOUND,
        UNAVAILABLE
    }

    public static ChainObservation finalized(String txSignature, long feeLamports) {
        return new ChainObservation(Status.FINALIZED, txSignature, feeLamports, 0);
    }

    public static ChainObservation notFound(long finalizedHeight) {
        return new ChainObservation(Status.NOT_FOUND, null, 0, finalizedHeight);
    }

    public static ChainObservation unavailable() {
        return new ChainObservation(Status.UNAVAILABLE, null, 0, 0);
    }
}
