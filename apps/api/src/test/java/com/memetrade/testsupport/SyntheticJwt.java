package com.memetrade.testsupport;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Synthetic JWT-shaped strings for fixtures. They are built at run time from fixed JSON so no token literal lives in
 * the source (the secret scan flags any JWT literal), and they are never signed by a real key.
 */
public final class SyntheticJwt {

    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private SyntheticJwt() {}

    public static String withSubject(String subject) {
        return segment("{\"alg\":\"HS256\"}") + "." + segment("{\"sub\":\"" + subject + "\"}") + "."
                + segment("signature-value");
    }

    private static String segment(String text) {
        return URL.encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
