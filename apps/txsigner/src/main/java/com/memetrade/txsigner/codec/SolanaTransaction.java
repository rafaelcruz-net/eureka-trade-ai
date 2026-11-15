package com.memetrade.txsigner.codec;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * A wire transaction: compact-u16 count of signatures, the 64-byte signatures, then the message. The number of
 * signature slots must equal the signatures the message requires; the size is limited to one packet (1232 bytes).
 */
public final class SolanaTransaction {

    public static final int MAX_TRANSACTION_SIZE = 1232;
    public static final int SIGNATURE_LENGTH = 64;
    private static final int MAX_BASE64_LENGTH = 4 * ((MAX_TRANSACTION_SIZE + 2) / 3);

    private final List<byte[]> signatures;
    private final byte[] messageBytes;
    private final SolanaMessage message;

    private SolanaTransaction(List<byte[]> signatures, byte[] messageBytes, SolanaMessage message) {
        this.signatures = signatures;
        this.messageBytes = messageBytes;
        this.message = message;
    }

    public static SolanaTransaction decode(byte[] wire) {
        if (wire == null || wire.length > MAX_TRANSACTION_SIZE) {
            throw new CodecException("Transaction larger than one packet");
        }
        ByteReader reader = new ByteReader(wire);
        int count = CompactU16.read(reader);
        if (count == 0 || (long) count * SIGNATURE_LENGTH > reader.remaining()) {
            throw new CodecException("Invalid number of signatures");
        }
        List<byte[]> signatures = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            signatures.add(reader.bytes(SIGNATURE_LENGTH));
        }
        byte[] messageBytes = reader.bytes(reader.remaining());
        SolanaMessage message = SolanaMessage.parse(messageBytes);
        if (message.header().requiredSignatures() != count) {
            throw new CodecException("The number of signatures differs from the message header");
        }
        return new SolanaTransaction(Collections.unmodifiableList(signatures), messageBytes, message);
    }

    public static SolanaTransaction decodeBase64(String base64) {
        if (base64 == null || base64.length() > MAX_BASE64_LENGTH) {
            throw new CodecException("Transaction larger than one packet");
        }
        try {
            return decode(Base64.getDecoder().decode(base64));
        } catch (IllegalArgumentException notBase64) {
            throw new CodecException("Transaction is not valid Base64");
        }
    }

    /** A copy with {@code signature} in slot {@code index}; the message bytes never change. */
    public SolanaTransaction withSignature(int index, byte[] signature) {
        if (index < 0 || index >= signatures.size()) {
            throw new IllegalArgumentException("No signature slot " + index);
        }
        if (signature == null || signature.length != SIGNATURE_LENGTH) {
            throw new IllegalArgumentException("A signature has 64 bytes");
        }
        List<byte[]> copy = new ArrayList<>(signatures);
        copy.set(index, signature.clone());
        return new SolanaTransaction(Collections.unmodifiableList(copy), messageBytes, message);
    }

    public byte[] serialize() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CompactU16.write(out, signatures.size());
        signatures.forEach(out::writeBytes);
        out.writeBytes(messageBytes);
        return out.toByteArray();
    }

    public String serializeBase64() {
        return Base64.getEncoder().encodeToString(serialize());
    }

    public List<byte[]> signatures() {
        return signatures.stream().map(byte[]::clone).toList();
    }

    public byte[] messageBytes() {
        return messageBytes.clone();
    }

    public SolanaMessage message() {
        return message;
    }
}
