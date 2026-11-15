package com.memetrade.botwallet.domain;

import com.memetrade.ledger.api.RealBalance;

/**
 * What can be withdrawn (BR4.3 to BR4.6), always from the ledger; the network balance only limits it further under a
 * DIVERGENCE pause. A partial withdrawal keeps the fee reserve and never leaves the wallet between zero and the
 * rent-exempt minimum; the total withdrawal empties the wallet (minus its own fee) when nothing else is in progress.
 */
public final class WithdrawalCalculator {

    private WithdrawalCalculator() {}

    public static WithdrawalAmount partial(RealBalance balance, long requested, WithdrawalLimits limits) {
        if (requested <= 0) {
            throw new BotWalletException(BotWalletError.VALIDATION_FAILED);
        }
        long free = free(balance, limits);
        long max = maxPartial(free, limits);
        if (requested > max) {
            throw new BotWalletException(BotWalletError.INSUFFICIENT_FREE_BALANCE, max);
        }
        long remaining = free - requested - limits.feeLamports();
        if (remaining > 0 && remaining < limits.rentExemptMinimumLamports()) {
            throw new BotWalletException(BotWalletError.INSUFFICIENT_FREE_BALANCE, max);
        }
        return new WithdrawalAmount(WithdrawalKind.PARTIAL, requested, limits.feeLamports());
    }

    public static WithdrawalAmount total(RealBalance balance, WithdrawalLimits limits) {
        if (limits.hasAmbiguousOperation()) {
            throw new BotWalletException(BotWalletError.ORDER_AMBIGUOUS);
        }
        long free = free(balance, limits);
        boolean nothingInProgress =
                !balance.hasOpenPositionOrOrder() && balance.openReservations().isEmpty();
        long amount = nothingInProgress ? free - limits.feeLamports() : maxPartial(free, limits);
        if (amount <= 0) {
            throw new BotWalletException(BotWalletError.INSUFFICIENT_FREE_BALANCE, 0L);
        }
        return new WithdrawalAmount(WithdrawalKind.TOTAL, amount, limits.feeLamports());
    }

    private static long free(RealBalance balance, WithdrawalLimits limits) {
        long ledgerFree = balance.freeLamports();
        return limits.networkBalanceLamports()
                .map(network -> Math.min(ledgerFree, network - balance.reservedLamports()))
                .orElse(ledgerFree);
    }

    private static long maxPartial(long free, WithdrawalLimits limits) {
        return Math.max(0, free - limits.feeReserveLamports() - limits.feeLamports());
    }
}
