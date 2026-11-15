package com.memetrade.realexecution.domain.solana;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A built swap message: its bytes, how many signatures it requires and its SHA-256 ({@code messageDigest} of the
 * order and of the signing session, contract R3).
 */
public record BuiltMessage(byte[] messageBytes, int signerCount) {

    public BuiltMessage {
        if (messageBytes == null || messageBytes.length == 0 || signerCount < 1 || signerCount > 2) {
            throw new IllegalArgumentException("A swap message has bytes and one or two signers");
        }
        messageBytes = messageBytes.clone();
    }

    @Override
    public byte[] messageBytes() {
        return messageBytes.clone();
    }

    public String digestHex() {
        return sha256Hex(messageBytes);
    }

    public int transactionSize() {
        return ShortVec.length(signerCount) + 64 * signerCount + messageBytes.length;
    }

    /** The wire transaction with one empty 64-byte slot per signer, as the TxSigner and the browser receive it. */
    public String unsignedTransactionBase64() {
        return WireTransaction.unsigned(messageBytes, signerCount).toBase64();
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
    }
}
