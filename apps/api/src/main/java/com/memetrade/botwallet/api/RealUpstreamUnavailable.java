package com.memetrade.botwallet.api;

import java.util.Objects;

/**
 * M1 and M3 for the REAL execution (U4, BR9.1): a provider of the bot wallet did not answer (the TxSigner or every RPC
 * provider). A technical failure, never a refusal; the message names the provider only, never a value.
 */
public class RealUpstreamUnavailable extends RuntimeException {

    /** The provider; its name is the label {@code provider} of {@code real_upstream_failures_total}. */
    public enum Provider {
        RPC,
        TXSIGNER
    }

    private final Provider provider;

    public RealUpstreamUnavailable(Provider provider) {
        super("REAL upstream unavailable: provider=" + provider);
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    public Provider provider() {
        return provider;
    }
}
