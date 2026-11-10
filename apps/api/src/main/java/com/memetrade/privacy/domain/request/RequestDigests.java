package com.memetrade.privacy.domain.request;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The idempotency of the request routes (NFR2.4): the digest of the content of a call (SHA-256, never the content) and
 * the stored answer, which is only the identifier of the request. A replay reads the request again.
 */
final class RequestDigests {

    /** The stored body may come back normalised by the database (key order, spaces). */
    private static final Pattern REQUEST_ID = Pattern.compile("\"requestId\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"");

    private RequestDigests() {}

    static String digest(String... parts) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                sha.update((part == null ? "\u0000" : part).getBytes(StandardCharsets.UTF_8));
                sha.update((byte) 0x1f);
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", impossible);
        }
    }

    static String stored(UUID requestId) {
        return "{\"requestId\":\"" + requestId + "\"}";
    }

    static UUID requestId(String body) {
        Matcher matcher = REQUEST_ID.matcher(body == null ? "" : body);
        if (!matcher.find()) {
            throw new IllegalStateException("A stored data request answer could not be read");
        }
        return UUID.fromString(matcher.group(1));
    }
}
