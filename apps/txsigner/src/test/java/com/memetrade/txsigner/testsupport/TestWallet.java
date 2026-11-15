package com.memetrade.txsigner.testsupport;

import com.memetrade.txsigner.codec.Base58;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;

/**
 * Ed25519 wallet created in TEST code only, fresh for every run (project.md): plays the main wallet of a person, which
 * signs the consent message like a browser wallet ({@code signMessage}, Ed25519 over the UTF-8 bytes).
 */
public final class TestWallet {

    private final KeyPair keyPair;
    private final byte[] publicKey;

    private TestWallet(KeyPair keyPair) {
        this.keyPair = keyPair;
        byte[] encoded = keyPair.getPublic().getEncoded();
        this.publicKey = Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
    }

    public static TestWallet generate() {
        try {
            return new TestWallet(KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        } catch (GeneralSecurityException missing) {
            throw new IllegalStateException(missing);
        }
    }

    public String address() {
        return Base58.encode(publicKey);
    }

    public byte[] publicKey() {
        return publicKey.clone();
    }

    public String sign(String message) {
        return Base58.encode(signRaw(message.getBytes(StandardCharsets.UTF_8)));
    }

    public byte[] signRaw(byte[] message) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(keyPair.getPrivate());
            signer.update(message);
            return signer.sign();
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
