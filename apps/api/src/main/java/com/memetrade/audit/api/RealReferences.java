package com.memetrade.audit.api;

/**
 * Typed REAL references of an audit event (M8, BR6.2): the transaction signature, the mint and the bot wallet address
 * go only here, in their own columns; in the free {@code details} they stay refused. Every field is optional. The MAIN
 * wallet never goes here: it travels in {@link AuditEventCommand#wallet()} and is stored only as its HMAC.
 */
public record RealReferences(String txSignature, String mint, String botWalletAddress) {

    public static RealReferences botWallet(String botWalletAddress) {
        return new RealReferences(null, null, botWalletAddress);
    }

    public static RealReferences transaction(String txSignature, String botWalletAddress) {
        return new RealReferences(txSignature, null, botWalletAddress);
    }
}
