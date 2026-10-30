package com.memetrade.identity.domain.session;

import com.memetrade.identity.domain.idempotency.RequestDigest;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** Opaque 256-bit refresh tokens; only their SHA-256 digest is persisted. */
public class RefreshTokens {

    private final SecureRandom random;

    public RefreshTokens(SecureRandom random) {
        this.random = random;
    }

    public String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String digest(String token) {
        return RequestDigest.sha256Hex(token.getBytes(StandardCharsets.UTF_8));
    }
}
