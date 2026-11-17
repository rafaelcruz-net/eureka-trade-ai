package com.memetrade.botwallet.api;

import java.time.Instant;
import java.util.Objects;

/**
 * M1 {@code readRelease} (261113-trading-real-bot U6, rules.md "Mudança de contrato" item 1): the release of one person
 * on the network of the environment, read only.
 *
 * @param realEnabled the REAL switch of the environment; when off, the route answers {@code 403 REAL_DISABLED}
 * @param network the network of the environment ({@code LOCALNET}, {@code DEVNET} or {@code MAINNET})
 * @param state {@code NONE}, {@code QUARANTINE}, {@code ACTIVE}, {@code BLOCKED} or {@code REVOKED}
 * @param effectiveAt the end of the quarantine; {@code null} for {@code NONE}
 * @param blockedReason the first red gate of a {@code BLOCKED} release; {@code null} otherwise
 */
public record RealReleaseInfo(
        boolean realEnabled, String network, String state, Instant effectiveAt, String blockedReason) {

    public RealReleaseInfo {
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(state, "state");
    }
}
