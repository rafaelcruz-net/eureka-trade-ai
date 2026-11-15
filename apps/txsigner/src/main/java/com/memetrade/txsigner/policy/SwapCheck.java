package com.memetrade.txsigner.policy;

/**
 * Input of the swap policy: the transaction in Base64 (in SIGNED with the signature of the main wallet in slot 0), the
 * bot wallet, the main wallet, the cap and the mode of the TxSigner's OWN WalletPolicy (never of the request), and the
 * context of the BotWallet.
 */
public record SwapCheck(
        String transactionBase64,
        String botWallet,
        String mainWallet,
        SwapContext context,
        long authorizedCapLamports,
        OperationMode authorizedMode) {}
