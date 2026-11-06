package com.memetrade.testsupport;

import com.memetrade.identity.domain.login.AdminWallets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TEST-ONLY list of administrator wallets that a test can change while the context runs (BR1.4: a wallet removed from
 * the configuration loses the access at the next refresh). Production reads the fixed list of the configuration.
 */
public final class MutableAdminWallets implements AdminWallets {

    private final Set<String> listed = ConcurrentHashMap.newKeySet();

    @Override
    public boolean isListed(String walletAddress) {
        return listed.contains(walletAddress);
    }

    public void add(String walletAddress) {
        listed.add(walletAddress);
    }

    public void remove(String walletAddress) {
        listed.remove(walletAddress);
    }

    public void clear() {
        listed.clear();
    }
}
