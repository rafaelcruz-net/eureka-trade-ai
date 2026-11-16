package com.memetrade.shared.wallet;

/**
 * 261113-trading-real-bot U5 (BR5.1, U5-NFR6.3): the one abbreviation of a wallet shown to an administrator: the 4
 * first and the 4 last characters around an ellipsis, as the masked form of {@link WalletAddress}; nothing for a wallet
 * that is gone (anonymized).
 */
public final class WalletDisplay {

    private WalletDisplay() {}

    public static String abbreviate(String address) {
        if (address == null || address.length() < 9) {
            return null;
        }
        return WalletAddress.mask(address);
    }
}
