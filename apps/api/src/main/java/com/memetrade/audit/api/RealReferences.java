package com.memetrade.audit.api;

import com.memetrade.shared.wallet.WalletDisplay;
import java.util.Objects;

/**
 * Typed REAL references of an audit event (M8, BR6.2): the transaction signature, the mint and the bot wallet address
 * go only here, in their own columns; in the free {@code details} they stay refused. Every field is optional. The MAIN
 * wallet never goes here: it travels in {@link AuditEventCommand#wallet()} and is stored only as its HMAC.
 *
 * <p>261121-paper-real-unico U2 (NFR3.9, D-NFR2): {@code hashedTxSignature} is a signature that must leave the
 * process only abbreviated and as its HMAC. Like the wallet of {@link AuditEventCommand}, it lives only in memory: the
 * publisher turns it into {@code txSignatureAbbrev} and {@code txSignatureHmac} in {@code details} and never writes it,
 * and {@link #toString()} never prints it.
 */
public record RealReferences(String txSignature, String mint, String botWalletAddress, String hashedTxSignature) {

    /** The references of every event before 261121-paper-real-unico U2: no hashed signature. */
    public RealReferences(String txSignature, String mint, String botWalletAddress) {
        this(txSignature, mint, botWalletAddress, null);
    }

    public static RealReferences botWallet(String botWalletAddress) {
        return new RealReferences(null, null, botWalletAddress);
    }

    public static RealReferences transaction(String txSignature, String botWalletAddress) {
        return new RealReferences(txSignature, null, botWalletAddress);
    }

    /**
     * 261121-paper-real-unico U2 (REAL_SLOT_CONFLICT): a signature that the trail keeps only abbreviated (4 … 4) and as
     * its HMAC; {@code audit_event.tx_signature} stays empty.
     */
    public static RealReferences hashedTransaction(String txSignature) {
        return new RealReferences(null, null, null, Objects.requireNonNull(txSignature, "txSignature"));
    }

    /**
     * The one abbreviation of a signature in the trail and in the logs: the 4 first and the 4 last characters around an
     * ellipsis, the form of {@link WalletDisplay#abbreviate}; {@code null} for a text too short to abbreviate.
     */
    public static String abbreviate(String txSignature) {
        return WalletDisplay.abbreviate(txSignature);
    }

    /** Never prints the hashed signature. */
    @Override
    public String toString() {
        return "RealReferences[txSignature=" + txSignature + ", mint=" + mint + ", botWalletAddress=" + botWalletAddress
                + ", hashedTxSignature=" + (hashedTxSignature == null ? "null" : "******") + "]";
    }
}
