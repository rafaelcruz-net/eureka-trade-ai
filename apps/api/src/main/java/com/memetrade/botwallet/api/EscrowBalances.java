package com.memetrade.botwallet.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 261113-trading-real-bot U7 (BR5.3b, U7-NFR4.8): declared by the M1, implemented by {@code realexits}. While a
 * conditional of the Jupiter is OPEN or CANCELLING, the token of the position is in the custody account of the order,
 * not in the bot wallet; the reconciliation adds the balance of each custody account, read on the network through the
 * account recorded at the creation, so an open conditional never becomes a divergence. A conditional of the simulated
 * adapter has no custody (the token never leaves the bot wallet) and is not listed. Without the module: none.
 */
@FunctionalInterface
public interface EscrowBalances {

    /** The custody accounts of the conditionals OPEN or CANCELLING of the bot wallet of this tenant. */
    List<Escrow> openEscrows(UUID tenantId, UUID botWalletId);

    /**
     * @param account the custody account recorded at the creation; empty when it was not recorded (the reconciliation
     *     cannot read it, so the wallet is UNAVAILABLE, never DIVERGENT)
     */
    record Escrow(String mint, Optional<String> account) {
        public Escrow {
            Objects.requireNonNull(mint, "mint");
            Objects.requireNonNull(account, "account");
        }
    }
}
