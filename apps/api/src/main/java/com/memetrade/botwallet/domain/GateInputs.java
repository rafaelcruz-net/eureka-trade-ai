package com.memetrade.botwallet.domain;

import java.util.Objects;

/**
 * 261113-trading-real-bot U5 (BR1.4): what the gates of one release read: its network and the network configured for
 * the api, the worker and the TxSigner (the same {@code memetrade.real.network}), the kill switch REAL, an open
 * divergence of the person, and the CI of the commit of the image (read once per run).
 */
public record GateInputs(
        Network releaseNetwork, Network environmentNetwork, boolean killSwitchOn, boolean divergenceOpen, CiResult ci) {

    public GateInputs {
        Objects.requireNonNull(releaseNetwork, "releaseNetwork");
        Objects.requireNonNull(environmentNetwork, "environmentNetwork");
        Objects.requireNonNull(ci, "ci");
    }
}
