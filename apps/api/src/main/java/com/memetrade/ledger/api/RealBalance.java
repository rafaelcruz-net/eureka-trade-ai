package com.memetrade.ledger.api;

import java.util.Set;

/**
 * Balance of a bot wallet read from the REAL ledger alone (BR3.3 to BR3.5): total of the balance entries, the reserved
 * amount (RESERVE minus RESERVE_RELEASE), the free balance and the references of reservations still open. Lamports
 * are {@code long}; every sum is checked for overflow.
 *
 * <p>261113-trading-real-bot U3 (BR5.1, M2): the reserved amount split by kind of reservation, by the prefix of its
 * reference: {@code order:} (an order not yet filled, ambiguous ones included; counts in the cap of the barrier) and
 * {@code wd:} (a withdrawal; leaves the balance, never exposure).
 */
public record RealBalance(
        long totalLamports,
        long reservedLamports,
        long freeLamports,
        Set<String> openReservations,
        boolean hasSwapFills,
        long orderReservedLamports,
        long withdrawalReservedLamports) {

    public RealBalance {
        openReservations = Set.copyOf(openReservations);
    }

    /** The balance as the U1 built it, without the split by kind of reservation (both parts zero). */
    public RealBalance(
            long totalLamports,
            long reservedLamports,
            long freeLamports,
            Set<String> openReservations,
            boolean hasSwapFills) {
        this(totalLamports, reservedLamports, freeLamports, openReservations, hasSwapFills, 0, 0);
    }

    /** BR3.3: what the bot may operate, never above the cap and never into the fee reserve. */
    public long operableLamports(long capLamports, long feeReserveLamports) {
        return Math.max(0, Math.min(freeLamports - feeReserveLamports, capLamports));
    }

    /** BR3.3: what is above the cap, available only to withdraw. */
    public long aboveCapLamports(long capLamports, long feeReserveLamports) {
        return Math.max(0, freeLamports - feeReserveLamports - capLamports);
    }

    /** BR4.4: a position (swap fills) or an order still holding a reservation, read from the ledger itself. */
    public boolean hasOpenPositionOrOrder() {
        return hasSwapFills || openReservations.stream().anyMatch(reference -> reference.startsWith("order:"));
    }
}
