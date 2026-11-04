package com.memetrade.ledger.domain;

import java.util.UUID;

/**
 * Test-only access to the package-private compensation mechanism (F10): in the beta no route nor screen calls it, only
 * the tests do (NFR6.4), from this package.
 */
public final class Compensations {

    private Compensations() {}

    /** Compensates {@code entryId} through {@code port}, inside the caller's transaction. */
    public static UUID compensate(CompensationPort port, UUID entryId, String reason) {
        return new LedgerCompensation(port).compensate(entryId, reason);
    }

    /** True when the mechanism refused the compensation (BR4.2). */
    public static boolean isRefusal(Throwable failure) {
        return failure instanceof LedgerCompensation.CompensationRefused;
    }
}
