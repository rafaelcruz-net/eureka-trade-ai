package com.memetrade.identity.domain.login;

import java.util.Set;

/**
 * The administrator wallets of the configuration ({@code ADMIN_WALLET_ADDRESSES}, BR1.1, BR1.4). Read on every
 * verification and every refresh, so a wallet removed from the list loses the access at its next refresh.
 */
@FunctionalInterface
public interface AdminWallets {

    boolean isListed(String walletAddress);

    /** The fixed list read once at start-up from the configuration. */
    static AdminWallets of(Set<String> addresses) {
        Set<String> copy = Set.copyOf(addresses);
        return copy::contains;
    }
}
