package com.memetrade.identity.domain.invite;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Invitation codes (BR1.1, BR2.3, NFR6.1, NFR6.2, NFR6.7): 256 random bits from the platform {@link SecureRandom},
 * base64url without padding (43 characters). Only the SHA-256 digest (hexadecimal) and the last 4 characters are ever
 * stored; lookups compare digests, never the code. Production has no other source of randomness: the alternative
 * generator exists only in the package-private constructor used by the tests.
 */
public final class InviteCodes {

    public static final int CODE_BYTES = 32;
    public static final int CODE_LENGTH = 43;
    public static final int SUFFIX_LENGTH = 4;

    private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9_-]{" + CODE_LENGTH + "}$");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final Consumer<byte[]> randomBytes;

    public InviteCodes(SecureRandom random) {
        this(Objects.requireNonNull(random, "random")::nextBytes);
    }

    /** Tests only: a fixed source of bytes for a known code. */
    InviteCodes(Consumer<byte[]> randomBytes) {
        this.randomBytes = randomBytes;
    }

    /** A new code; it leaves the api only once, in the 201 answer of the issuance (BR1.3). */
    public String newCode() {
        byte[] bytes = new byte[CODE_BYTES];
        randomBytes.accept(bytes);
        return ENCODER.encodeToString(bytes);
    }

    /** True for 43 characters of the base64url alphabet; anything else is treated as an unknown code (NFR6.7). */
    public static boolean isWellFormed(String code) {
        return code != null && FORMAT.matcher(code).matches();
    }

    /** SHA-256 of the code, lower-case hexadecimal (64 characters): the only form stored and compared. */
    public static String digest(String code) {
        requireWellFormed(code);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", missing);
        }
    }

    /** The last 4 characters, so the administrator recognises an invitation in the list. */
    public static String suffix(String code) {
        requireWellFormed(code);
        return code.substring(CODE_LENGTH - SUFFIX_LENGTH);
    }

    private static void requireWellFormed(String code) {
        if (!isWellFormed(code)) {
            // Never echoes the value: it may be a real code.
            throw new IllegalArgumentException("Not a well-formed invitation code");
        }
    }
}
