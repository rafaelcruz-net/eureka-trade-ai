package com.memetrade.testsupport;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;

/**
 * Ed25519 wallet created in TEST code only, fresh for every run (project.md: key generation never exists in
 * production code). Exposes the Base58 public address and signs messages like a browser wallet would.
 */
public final class TestWallet {

    private final KeyPair keyPair;
    private final String address;

    private TestWallet(KeyPair keyPair) {
        this.keyPair = keyPair;
        byte[] encoded = keyPair.getPublic().getEncoded();
        this.address = SyntheticIds.encode(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));
    }

    public static TestWallet generate() {
        try {
            return new TestWallet(KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        } catch (GeneralSecurityException missing) {
            throw new IllegalStateException(missing);
        }
    }

    public String address() {
        return address;
    }

    public String sign(String message) {
        return SyntheticIds.encode(signRaw(message));
    }

    public byte[] signRaw(String message) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(keyPair.getPrivate());
            signer.update(message.getBytes(StandardCharsets.UTF_8));
            return signer.sign();
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
