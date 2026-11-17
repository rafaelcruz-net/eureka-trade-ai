package com.memetrade.botwallet.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * M1 reads (261113-trading-real-bot U3, BR7.3): the REAL state of a bot wallet for the barrier, and the wallets the jobs
 * of the barrier go through. Every read of one person filters by tenant and person; a wallet of another tenant is simply
 * not found (BR6.3).
 */
public interface RealStateQuery {

    /** The state of the ACTIVE wallet of this person in this tenant; empty when there is none. */
    Optional<RealStateView> readRealState(UUID tenantId, UUID userId);

    /**
     * BR4.4 and F4: every ACTIVE wallet whose person has a release in force on the network of the environment; empty
     * while the REAL is switched off.
     */
    List<RealWalletRef> walletsInForce();

    /**
     * 261113-trading-real-bot U6 (rules.md "Mudança de contrato" item 1): the release of this person on the network of
     * the environment, with the REAL switch; read only, whether or not the person has a bot wallet.
     */
    RealReleaseInfo readRelease(UUID tenantId, UUID userId);

    /** BR4.5: every ACTIVE wallet paused for {@code reason}, with the start of that pause. */
    List<PausedWallet> pausedWallets(RealPauseReason reason);

    /** One wallet paused for a reason and the instant that pause started. */
    record PausedWallet(RealWalletRef wallet, Instant since) {
        public PausedWallet {
            Objects.requireNonNull(wallet, "wallet");
            Objects.requireNonNull(since, "since");
        }
    }
}
