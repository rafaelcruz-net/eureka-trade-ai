package com.memetrade.ledger.domain;

import com.memetrade.ledger.api.RealEntry;
import com.memetrade.ledger.api.RealEntryKind;

/**
 * Sign convention of BR3.5, checked before writing (the database checks the sign again). Since the U3 of
 * 261113-trading-real-bot a swap fill also needs its mint and a positive quantity, and its sign gives its direction
 * (IN negative, OUT positive), so the positions of M2 can be derived from the ledger alone.
 */
public final class RealEntryRules {

    private RealEntryRules() {}

    public static void validate(RealEntry entry) {
        long lamports = entry.lamports();
        boolean valid =
                switch (entry.kind()) {
                    case DEPOSIT, RESERVE, RESERVE_RELEASE -> lamports > 0;
                    case WITHDRAWAL, NETWORK_FEE, RENT -> lamports < 0;
                    case COMPENSATION -> compensationIsValid(entry);
                    case SWAP_FILL_IN -> lamports < 0 && hasToken(entry);
                    case SWAP_FILL_OUT -> lamports > 0 && hasToken(entry);
                };
        if (!valid) {
            throw new IllegalArgumentException("REAL entry " + entry.kind() + " breaks the sign convention");
        }
    }

    /**
     * 261113-trading-real-bot U5 (BR2.3): SOL in lamports (no token) or one mint in base units with zero lamports, both
     * with a sign and never zero.
     */
    private static boolean compensationIsValid(RealEntry entry) {
        if (entry.mint() == null) {
            return entry.lamports() != 0 && entry.tokenBaseUnits() == null;
        }
        return !entry.mint().isBlank()
                && entry.lamports() == 0
                && entry.tokenBaseUnits() != null
                && entry.tokenBaseUnits() != 0;
    }

    private static boolean hasToken(RealEntry entry) {
        return entry.mint() != null
                && !entry.mint().isBlank()
                && entry.tokenBaseUnits() != null
                && entry.tokenBaseUnits() > 0
                && (entry.kind() == RealEntryKind.SWAP_FILL_IN || entry.kind() == RealEntryKind.SWAP_FILL_OUT);
    }
}
