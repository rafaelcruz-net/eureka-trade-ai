package com.memetrade.ledger.application;

import com.memetrade.identity.api.IdempotencyKey;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Canonical content digests (BR3.4) and the mapping of a C15 key to the UUID of the common mechanism. */
final class Digests {

    private Digests() {}

    /** SHA-256 of the fields joined by {@code |}; decimals in plain notation without trailing zeros. */
    static String sha256(Object... fields) {
        StringBuilder canonical = new StringBuilder();
        for (Object field : fields) {
            if (!canonical.isEmpty()) {
                canonical.append('|');
            }
            canonical.append(field instanceof BigDecimal decimal ? plain(decimal) : String.valueOf(field));
        }
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", missing);
        }
    }

    /**
     * The key of C15 is text; the common mechanism takes a UUID. A canonical UUID is used as is; any other text maps
     * to the name-based UUID of {@code paper:<key>}, deterministic and therefore idempotent.
     */
    static IdempotencyKey key(String idempotencyKey) {
        return key(idempotencyKey, DEFAULT_MAX_KEY_LENGTH);
    }

    /** Default length bound of a C15 key ({@code fund}). */
    static final int DEFAULT_MAX_KEY_LENGTH = 150;

    /**
     * As {@link #key(String)} with an operation-specific bound; refused (validation error) before anything is written.
     */
    static IdempotencyKey key(String idempotencyKey, int maxLength) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > maxLength) {
            throw new IllegalArgumentException(
                    "The idempotency key is required and has at most " + maxLength + " characters");
        }
        try {
            UUID parsed = UUID.fromString(idempotencyKey);
            if (parsed.toString().equalsIgnoreCase(idempotencyKey)) {
                return new IdempotencyKey(parsed);
            }
        } catch (IllegalArgumentException notUuid) {
            // Not a UUID: the deterministic name-based UUID below represents it.
            return new IdempotencyKey(nameBased(idempotencyKey));
        }
        return new IdempotencyKey(nameBased(idempotencyKey));
    }

    private static UUID nameBased(String key) {
        return UUID.nameUUIDFromBytes(("paper:" + key).getBytes(StandardCharsets.UTF_8));
    }

    static String plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }
}
