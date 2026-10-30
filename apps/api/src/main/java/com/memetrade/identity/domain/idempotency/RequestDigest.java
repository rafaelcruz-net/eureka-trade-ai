package com.memetrade.identity.domain.idempotency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 digest of the canonical JSON request content (key order does not matter, values do). */
public final class RequestDigest {

    private static final ObjectMapper CANONICAL =
            new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private RequestDigest() {}

    public static String ofJson(byte[] body) {
        return sha256Hex(canonical(body));
    }

    private static byte[] canonical(byte[] body) {
        if (body.length == 0) {
            return body;
        }
        try {
            JsonNode tree = CANONICAL.readTree(body);
            Object sorted = CANONICAL.treeToValue(tree, Object.class);
            return CANONICAL.writeValueAsString(sorted).getBytes(StandardCharsets.UTF_8);
        } catch (IOException notJson) {
            // Not JSON: the raw bytes are the content (the controller will reject it anyway).
            return body;
        }
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", missing);
        }
    }
}
