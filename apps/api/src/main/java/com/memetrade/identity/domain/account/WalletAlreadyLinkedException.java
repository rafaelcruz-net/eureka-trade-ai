package com.memetrade.identity.domain.account;

/**
 * The wallet got an account in a concurrent creation (BR3.7): the unique constraint of {@code wallet.address} is the
 * final guarantee. Thrown out of the verification transaction so it rolls back; the caller then records the refusal
 * (challenge CONSUMED and one LOGIN_REJECTED) in a NEW transaction and answers {@code 409 WALLET_ALREADY_LINKED}.
 */
public class WalletAlreadyLinkedException extends RuntimeException {

    public WalletAlreadyLinkedException(Throwable cause) {
        super("The wallet is already linked to an account", cause);
    }
}
