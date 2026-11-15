package com.memetrade.realexecution.domain.solana;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * The wire form of a transaction: compact count of signatures, the 64-byte signatures, the message (U4-NFR6.2). Used
 * to read the transaction the browser sends back, whose message is compared BYTE BY BYTE with the recorded one, and to
 * put the TxSigner answer together. Strict: truncation, a count without room and more than one packet are refused.
 */
public final class WireTransaction {

    private static final int SIGNATURE = 64;

    private final List<byte[]> signatures;
    private final byte[] messageBytes;

    private WireTransaction(List<byte[]> signatures, byte[] messageBytes) {
        this.signatures = signatures;
        this.messageBytes = messageBytes;
    }

    public static WireTransaction unsigned(byte[] message, int signerCount) {
        List<byte[]> empty = new ArrayList<>();
        for (int i = 0; i < signerCount; i++) {
            empty.add(new byte[SIGNATURE]);
        }
        return new WireTransaction(List.copyOf(empty), message.clone());
    }

    public static WireTransaction decodeBase64(String base64) {
        if (base64 == null || base64.length() > 4 * ((MessageBuilder.MAX_TRANSACTION_SIZE + 2) / 3)) {
            throw new IllegalArgumentException("Transaction larger than one packet");
        }
        try {
            return decode(Base64.getDecoder().decode(base64));
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalArgumentException("Not a Base64 transaction");
        }
    }

    public static WireTransaction decode(byte[] wire) {
        if (wire == null || wire.length > MessageBuilder.MAX_TRANSACTION_SIZE) {
            throw new IllegalArgumentException("Transaction larger than one packet");
        }
        int[] count = ShortVec.read(wire, 0);
        int position = count[1];
        if (count[0] == 0 || (long) count[0] * SIGNATURE >= wire.length - position) {
            throw new IllegalArgumentException("Invalid number of signatures");
        }
        List<byte[]> signatures = new ArrayList<>();
        for (int i = 0; i < count[0]; i++) {
            signatures.add(Arrays.copyOfRange(wire, position, position + SIGNATURE));
            position += SIGNATURE;
        }
        return new WireTransaction(List.copyOf(signatures), Arrays.copyOfRange(wire, position, wire.length));
    }

    public List<byte[]> signatures() {
        return signatures.stream().map(byte[]::clone).toList();
    }

    public byte[] messageBytes() {
        return messageBytes.clone();
    }

    /** A copy with {@code signature} in slot {@code index}; the message never changes. */
    public WireTransaction withSignature(int index, byte[] signature) {
        if (index < 0 || index >= signatures.size() || signature == null || signature.length != SIGNATURE) {
            throw new IllegalArgumentException("No such signature slot or not a 64-byte signature");
        }
        List<byte[]> copy = new ArrayList<>(signatures);
        copy.set(index, signature.clone());
        return new WireTransaction(List.copyOf(copy), messageBytes);
    }

    public byte[] serialize() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ShortVec.write(out, signatures.size());
        signatures.forEach(out::writeBytes);
        out.writeBytes(messageBytes);
        return out.toByteArray();
    }

    public String toBase64() {
        return Base64.getEncoder().encodeToString(serialize());
    }
}
