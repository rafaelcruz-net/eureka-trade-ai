package com.memetrade.identity.domain.signature;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;

/**
 * Verifies a Solana wallet signature over the exact login message with the JDK's native Ed25519
 * ({@code Signature.getInstance("Ed25519")}). Verification only: no key generation or import of secret material.
 */
public final class Ed25519SignatureVerifier {

    public static final int PUBLIC_KEY_BYTES = 32;
    public static final int SIGNATURE_BYTES = 64;

    /** DER prefix of an X.509 SubjectPublicKeyInfo for Ed25519 (OID 1.3.101.112) followed by the 32 raw bytes. */
    private static final byte[] X509_ED25519_PREFIX = {
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    };

    public enum Result {
        VALID,
        INVALID,
        MALFORMED
    }

    /**
     * @param walletAddress Base58 public key of the wallet (32 bytes)
     * @param message exact UTF-8 text that was signed
     * @param signature Base58 signature (64 bytes)
     */
    public Result verify(String walletAddress, String message, String signature) {
        byte[] publicKeyBytes;
        byte[] signatureBytes;
        try {
            publicKeyBytes = Base58.decodeExact(walletAddress, PUBLIC_KEY_BYTES);
            signatureBytes = Base58.decodeExact(signature, SIGNATURE_BYTES);
        } catch (Base58.InvalidEncodingException malformed) {
            return Result.MALFORMED;
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey(publicKeyBytes));
            verifier.update(message.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(signatureBytes) ? Result.VALID : Result.INVALID;
        } catch (InvalidKeyException | InvalidKeySpecException notACurvePoint) {
            return Result.MALFORMED;
        } catch (SignatureException rejected) {
            return Result.INVALID;
        } catch (GeneralSecurityException platform) {
            throw new IllegalStateException("Ed25519 is not available on this JVM", platform);
        }
    }

    private static PublicKey publicKey(byte[] raw) throws GeneralSecurityException {
        byte[] encoded = new byte[X509_ED25519_PREFIX.length + raw.length];
        System.arraycopy(X509_ED25519_PREFIX, 0, encoded, 0, X509_ED25519_PREFIX.length);
        System.arraycopy(raw, 0, encoded, X509_ED25519_PREFIX.length, raw.length);
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
    }
}
