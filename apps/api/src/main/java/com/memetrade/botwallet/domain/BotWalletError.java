package com.memetrade.botwallet.domain;

/** Domain errors of the bot wallet with their HTTP status (contract-summary.md "Códigos de erro novos"). */
public enum BotWalletError {
    REAL_DISABLED(403),
    REAL_NOT_RELEASED(403),
    TERMS_REACCEPTANCE_REQUIRED(403),
    BOT_WALLET_ALREADY_ACTIVE(409),
    BOT_WALLET_NOT_ACTIVE(409),
    REAL_PAUSED(409),
    REAL_NOT_PAUSED(409),
    ORDER_AMBIGUOUS(409),
    CONSENT_INVALID(422),
    CONSENT_EXPIRED(422),
    CAP_ABOVE_MAXIMUM(422),
    INSUFFICIENT_FREE_BALANCE(422),
    /** 261113-trading-real-bot U5 (BR2.4): above the smaller balance while a divergence or the restore is open. */
    WITHDRAWAL_LIMITED_BY_DIVERGENCE(422),
    WITHDRAWAL_DESTINATION_INVALID(422),
    SIGNING_POLICY_REFUSED(422),
    VALIDATION_FAILED(400),
    NOT_FOUND(404),
    UPSTREAM_UNAVAILABLE(503);

    private final int status;

    BotWalletError(int status) {
        this.status = status;
    }

    public int status() {
        return status;
    }
}
