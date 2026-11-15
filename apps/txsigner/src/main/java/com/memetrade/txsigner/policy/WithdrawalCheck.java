package com.memetrade.txsigner.policy;

/**
 * Input of the withdrawal policy: the unsigned wire transaction in Base64, the bot wallet and the main wallet of the
 * TxSigner's own WalletPolicy (never of the request), the context of the BotWallet and the fee ceiling of the policy.
 */
public record WithdrawalCheck(
        String transactionBase64,
        String botWallet,
        String mainWallet,
        WithdrawalContext context,
        long maxWithdrawalFeeLamports) {}
