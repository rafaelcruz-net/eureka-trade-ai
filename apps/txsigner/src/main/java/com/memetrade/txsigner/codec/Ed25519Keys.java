package com.memetrade.txsigner.codec;

import java.util.Arrays;

/**
 * Conversion between the DER SubjectPublicKeyInfo of an Ed25519 PUBLIC key (what KMS GetPublicKey returns) and the
 * raw 32 bytes of a Solana address. Public keys only: there is no conversion of private material anywhere.
 */
public final class Ed25519Keys {

    public static final int RAW_LENGTH = 32;

    /** DER prefix of SubjectPublicKeyInfo for Ed25519 (OID 1.3.101.112) followed by the 32 raw bytes. */
    private static final byte[] SPKI_PREFIX = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};

    private Ed25519Keys() {}

    /** Raw 32 bytes of an Ed25519 SubjectPublicKeyInfo; any other key type or length is refused. */
    public static byte[] rawFromSpki(byte[] der) {
        if (der == null || der.length != SPKI_PREFIX.length + RAW_LENGTH) {
            throw new IllegalArgumentException("Not an Ed25519 public key: unexpected length");
        }
        if (!Arrays.equals(der, 0, SPKI_PREFIX.length, SPKI_PREFIX, 0, SPKI_PREFIX.length)) {
            throw new IllegalArgumentException("Not an Ed25519 public key: unexpected algorithm");
        }
        return Arrays.copyOfRange(der, SPKI_PREFIX.length, der.length);
    }

    /** SubjectPublicKeyInfo of a raw 32-byte public key, for the JDK verifier. */
    public static byte[] spkiFromRaw(byte[] raw) {
        if (raw == null || raw.length != RAW_LENGTH) {
            throw new IllegalArgumentException("An Ed25519 public key has 32 bytes");
        }
        byte[] der = Arrays.copyOf(SPKI_PREFIX, SPKI_PREFIX.length + RAW_LENGTH);
        System.arraycopy(raw, 0, der, SPKI_PREFIX.length, RAW_LENGTH);
        return der;
    }
}
