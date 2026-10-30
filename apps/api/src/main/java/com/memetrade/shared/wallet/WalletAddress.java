package com.memetrade.shared.wallet;

import java.util.regex.Pattern;

/**
 * Public Solana wallet address (Base58, 32 to 44 characters). First masking barrier (security-design.md, Q9):
 * {@link #toString()} is always masked (4 + 4 characters); the full value only leaves through {@link #full()},
 * which is used for the database and the audit trail {@code details}.
 */
public record WalletAddress(String value) {

    private static final Pattern BASE58_ADDRESS = Pattern.compile("^[1-9A-HJ-NP-Za-km-z]{32,44}$");

    public WalletAddress {
        if (value == null || !BASE58_ADDRESS.matcher(value).matches()) {
            throw new IllegalArgumentException("Not a Base58 wallet address");
        }
    }

    public static WalletAddress of(String value) {
        return new WalletAddress(value);
    }

    public static boolean isValid(String value) {
        return value != null && BASE58_ADDRESS.matcher(value).matches();
    }

    /** Full address: only for persistence and audit {@code details}. */
    public String full() {
        return value;
    }

    /** Masked form for logs, metrics, actors and messages. */
    public String masked() {
        return mask(value);
    }

    public static String mask(String address) {
        return address.substring(0, 4) + "…" + address.substring(address.length() - 4);
    }

    @Override
    public String toString() {
        return masked();
    }
}
