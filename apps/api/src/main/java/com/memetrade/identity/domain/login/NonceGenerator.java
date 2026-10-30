package com.memetrade.identity.domain.login;

import java.security.SecureRandom;
import java.util.Base64;

/** 128-bit random nonce from {@link SecureRandom}, Base64url without padding (22 characters). */
public class NonceGenerator {

    public static final int NONCE_BYTES = 16;

    private final SecureRandom random;

    public NonceGenerator(SecureRandom random) {
        this.random = random;
    }

    public String next() {
        byte[] bytes = new byte[NONCE_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
