package com.memetrade.botwallet.domain;

import java.util.Optional;

/**
 * Inputs of the withdrawable calculation: the fee reserve, the fee of this withdrawal, the rent-exempt minimum (read
 * from the network, configurable), the network balance when the REAL is paused by DIVERGENCE (BR4.6) and whether an
 * ambiguous withdrawal or order is still open (BR4.4).
 */
public record WithdrawalLimits(
        long feeReserveLamports,
        long feeLamports,
        long rentExemptMinimumLamports,
        Optional<Long> networkBalanceLamports,
        boolean hasAmbiguousOperation) {}
