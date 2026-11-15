package com.memetrade.botwallet.domain;

/** A refusal of the bot wallet domain; INSUFFICIENT_FREE_BALANCE also names the maximum withdrawable (BR4.5). */
public class BotWalletException extends RuntimeException {

    private final BotWalletError error;
    private final Long maxWithdrawableLamports;

    public BotWalletException(BotWalletError error) {
        this(error, null);
    }

    public BotWalletException(BotWalletError error, Long maxWithdrawableLamports) {
        super(error.name());
        this.error = error;
        this.maxWithdrawableLamports = maxWithdrawableLamports;
    }

    public BotWalletError error() {
        return error;
    }

    public Long maxWithdrawableLamports() {
        return maxWithdrawableLamports;
    }
}
