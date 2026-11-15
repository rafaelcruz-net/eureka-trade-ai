package com.memetrade.txsigner.policy;

import com.memetrade.txsigner.codec.Ed25519Keys;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;

/**
 * Verifies a pure Ed25519 signature with the JDK's native implementation (project.md: no Solana SDK in production).
 * Verification only: there is no signing nor key generation here.
 */
public final class Ed25519Verifier {

    public static final int SIGNATURE_LENGTH = 64;

    /** {@code true} only when the 64-byte signature is valid for the raw 32-byte public key over the message. */
    public boolean verify(byte[] publicKey, byte[] message, byte[] signature) {
        if (publicKey == null || publicKey.length != Ed25519Keys.RAW_LENGTH) {
            return false;
        }
        if (signature == null || signature.length != SIGNATURE_LENGTH || message == null) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(Ed25519Keys.spkiFromRaw(publicKey))));
            verifier.update(message);
            return verifier.verify(signature);
        } catch (InvalidKeyException | InvalidKeySpecException | SignatureException notValid) {
            return false;
        } catch (GeneralSecurityException platform) {
            throw new IllegalStateException("Ed25519 is not available on this JVM", platform);
        }
    }
}
