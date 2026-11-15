package com.memetrade.botwallet.domain;

/**
 * BR1.9, BR1.7, BR2.1: the value that does not change goes into the challenge with its current value. ACTIVATE carries
 * SIGNED and the chosen cap; RAISE_CAP the current mode and a higher cap; RAISE_MODE a more permissive mode and the
 * current cap. A cap never passes the maximum of the profile (at most 10 SOL).
 */
public final class ChallengeRules {

    private ChallengeRules() {}

    public static ChallengeValues values(
            ConsentKind kind, OperationMode mode, Long capLamports, Preferences current, long maxCapLamports) {
        return switch (kind) {
            case ACTIVATE -> new ChallengeValues(OperationMode.SIGNED, cap(capLamports, maxCapLamports));
            case RAISE_CAP -> {
                Preferences existing = requireWallet(current);
                long cap = cap(capLamports, existing.maxCapLamports());
                if (cap <= existing.capLamports()) {
                    throw new BotWalletException(BotWalletError.VALIDATION_FAILED);
                }
                yield new ChallengeValues(existing.operationMode(), cap);
            }
            case RAISE_MODE -> {
                Preferences existing = requireWallet(current);
                if (mode == null || !mode.isMorePermissiveThan(existing.operationMode())) {
                    throw new BotWalletException(BotWalletError.VALIDATION_FAILED);
                }
                yield new ChallengeValues(mode, existing.capLamports());
            }
        };
    }

    private static long cap(Long capLamports, long maxCapLamports) {
        if (capLamports == null || capLamports < 0) {
            throw new BotWalletException(BotWalletError.VALIDATION_FAILED);
        }
        if (capLamports > maxCapLamports) {
            throw new BotWalletException(BotWalletError.CAP_ABOVE_MAXIMUM);
        }
        return capLamports;
    }

    private static Preferences requireWallet(Preferences current) {
        if (current == null) {
            throw new BotWalletException(BotWalletError.BOT_WALLET_NOT_ACTIVE);
        }
        return current;
    }
}
