package com.memetrade.txsigner.policy;

/**
 * What the TxSigner expects of one consent: the text and signature received, the main wallet the signature must come
 * from, the purpose of the request and the bot wallet line ({@code NEW} on activation, the target wallet otherwise).
 */
public record ConsentCheck(
        String message,
        String signature,
        String expectedMainWallet,
        ConsentPurpose expectedPurpose,
        String expectedBotWallet) {

    public static final String NEW_WALLET = "NEW";

    public static ConsentCheck activation(String message, String signature, String mainWallet) {
        return new ConsentCheck(message, signature, mainWallet, ConsentPurpose.ACTIVATE, NEW_WALLET);
    }

    public static ConsentCheck change(
            String message, String signature, String mainWallet, ConsentPurpose purpose, String botWallet) {
        return new ConsentCheck(message, signature, mainWallet, purpose, botWallet);
    }
}
