package com.memetrade.botwallet.domain;

import java.util.Optional;

/** BR1.1 to BR1.4, in this order: REAL on, terms v3 accepted, release in force, no active wallet yet. */
public final class ActivationGate {

    private ActivationGate() {}

    public static Optional<BotWalletError> check(
            boolean realEnabled, boolean termsAccepted, boolean released, boolean hasActiveWallet) {
        if (!realEnabled) {
            return Optional.of(BotWalletError.REAL_DISABLED);
        }
        if (!termsAccepted) {
            return Optional.of(BotWalletError.TERMS_REACCEPTANCE_REQUIRED);
        }
        if (!released) {
            return Optional.of(BotWalletError.REAL_NOT_RELEASED);
        }
        if (hasActiveWallet) {
            return Optional.of(BotWalletError.BOT_WALLET_ALREADY_ACTIVE);
        }
        return Optional.empty();
    }
}
