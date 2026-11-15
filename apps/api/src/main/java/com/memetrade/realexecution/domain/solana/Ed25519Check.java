package com.memetrade.realexecution.domain.solana;

import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;

/**
 * Pure Ed25519 verification with the native JDK implementation (project.md: no Solana SDK in production, U4-NFR6.2):
 * the server checks the signature of the main wallet over the recorded message before anything else. Verification
 * only: nothing here signs nor creates a key.
 */
public final class Ed25519Check {

    /** DER prefix of an Ed25519 SubjectPublicKeyInfo (OID 1.3.101.112), followed by the 32 raw bytes. */
    private static final byte[] SPKI_PREFIX = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};

    private Ed25519Check() {}

    /** {@code true} only when the 64-byte signature of the 32-byte key is valid over {@code message}. */
    public static boolean verify(byte[] publicKey, byte[] message, byte[] signature) {
        if (publicKey == null || publicKey.length != 32 || message == null) {
            return false;
        }
        if (signature == null || signature.length != 64) {
            return false;
        }
        byte[] spki = new byte[SPKI_PREFIX.length + 32];
        System.arraycopy(SPKI_PREFIX, 0, spki, 0, SPKI_PREFIX.length);
        System.arraycopy(publicKey, 0, spki, SPKI_PREFIX.length, 32);
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki)));
            verifier.update(message);
            return verifier.verify(signature);
        } catch (InvalidKeyException | InvalidKeySpecException | SignatureException notValid) {
            return false;
        } catch (GeneralSecurityException platform) {
            throw new IllegalStateException("Ed25519 is not available on this JVM", platform);
        }
    }
}
