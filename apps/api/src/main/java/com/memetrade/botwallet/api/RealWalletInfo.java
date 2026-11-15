package com.memetrade.botwallet.api;

import java.util.Objects;
import java.util.UUID;

/**
 * M1 for the REAL execution (U4): what the server needs to build a swap message for the ACTIVE bot wallet of a person.
 * The main wallet is the registered one (fixed at creation); it is the fee payer of the SIGNED mode (BR4.2) and goes
 * only into the message, never into a log, a metric or a free audit field.
 */
public record RealWalletInfo(
        UUID botWalletId,
        String botWalletAddress,
        String mainWalletAddress,
        RealOperationMode mode,
        int maxSlippageBps,
        long capLamports) {

    public RealWalletInfo {
        Objects.requireNonNull(botWalletId, "botWalletId");
        Objects.requireNonNull(botWalletAddress, "botWalletAddress");
        Objects.requireNonNull(mainWalletAddress, "mainWalletAddress");
        Objects.requireNonNull(mode, "mode");
    }
}
