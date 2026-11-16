package com.memetrade.botwallet.domain;

import com.memetrade.ledger.api.RealBalance;
import java.util.Optional;

/**
 * 261113-trading-real-bot U5 (BR2.4, S15, U5-NFR4.5): the limit of a withdrawal while the ledger and the network may
 * disagree. With a divergence OPEN or COMPENSATED of the wallet, or the restore pause (RESTORE or its pending check),
 * the withdrawable amount is the smaller of the free balance of the ledger and the SOL of the system account measured by
 * the last reconciliation (minus what is reserved), minus the fee reserve and the fee; the total withdrawal uses it too.
 * A pure read: it depends on no job, and without a measure the ledger decides, so the withdrawal is never unavailable.
 * A value above the limit (but within the ledger) is refused WITHDRAWAL_LIMITED_BY_DIVERGENCE with the maximum.
 */
public final class WithdrawalLimit {

    private WithdrawalLimit() {}

    /**
     * @param divergenceOpen the wallet has a divergence OPEN or COMPENSATED
     * @param restorePaused the global restore pause (or its pending check) is on
     * @param lastChainLamports the SOL measured on the network by the last reconciliation that compared
     */
    public record Context(boolean divergenceOpen, boolean restorePaused, Optional<Long> lastChainLamports) {}

    /** The network balance that limits the withdrawal, when the limit applies and a measure exists. */
    public static Optional<Long> networkBalance(Context context) {
        return context.divergenceOpen() || context.restorePaused() ? context.lastChainLamports() : Optional.empty();
    }

    public static WithdrawalAmount partial(
            RealBalance balance, long requested, WithdrawalLimits limits, Context context) {
        Optional<Long> network = networkBalance(context);
        if (network.isEmpty()) {
            return WithdrawalCalculator.partial(balance, requested, limits);
        }
        try {
            return WithdrawalCalculator.partial(balance, requested, limited(limits, network.get()));
        } catch (BotWalletException refused) {
            if (refused.error() != BotWalletError.INSUFFICIENT_FREE_BALANCE) {
                throw refused;
            }
            WithdrawalCalculator.partial(balance, requested, limits);
            throw new BotWalletException(
                    BotWalletError.WITHDRAWAL_LIMITED_BY_DIVERGENCE, refused.maxWithdrawableLamports());
        }
    }

    public static WithdrawalAmount total(RealBalance balance, WithdrawalLimits limits, Context context) {
        Optional<Long> network = networkBalance(context);
        if (network.isEmpty()) {
            return WithdrawalCalculator.total(balance, limits);
        }
        if (limits.hasAmbiguousOperation()) {
            throw new BotWalletException(BotWalletError.ORDER_AMBIGUOUS);
        }
        long free = Math.min(balance.freeLamports(), network.get() - balance.reservedLamports());
        long amount = free - limits.feeReserveLamports() - limits.feeLamports();
        if (amount <= 0) {
            throw new BotWalletException(BotWalletError.WITHDRAWAL_LIMITED_BY_DIVERGENCE, 0L);
        }
        return new WithdrawalAmount(WithdrawalKind.TOTAL, amount, limits.feeLamports());
    }

    private static WithdrawalLimits limited(WithdrawalLimits limits, long networkLamports) {
        return new WithdrawalLimits(
                limits.feeReserveLamports(),
                limits.feeLamports(),
                limits.rentExemptMinimumLamports(),
                Optional.of(networkLamports),
                limits.hasAmbiguousOperation());
    }
}
