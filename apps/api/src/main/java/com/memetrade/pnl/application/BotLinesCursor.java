package com.memetrade.pnl.application;

import com.memetrade.pnl.api.RealPnlLineCursorRejected;
import com.memetrade.pnl.api.RealPnlLineOrigin;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 261121-paper-real-unico U3 (C5; BR4.3, NFR1.11, NFR2.3, NFR3.7; performance-design.md "Forma do cursor"): the opaque
 * cursor of the lines of a REAL bot.
 *
 * <pre>
 * cursor = base64url_without_padding( 0x01 || botId[16 bytes] || SHA-256("pnl-line:" + idempotencyKey + "|" + origin)[0..16) )
 * </pre>
 *
 * 33 bytes, 44 characters. The anchor is a digest of the identity of the line (the idempotency key of its first ledger
 * row and its origin), so the cursor carries no signature, slot, mint nor address. Parsing is strict and happens before
 * any read: a length other than 44, a character outside the alphabet, a version other than {@code 0x01} or a bot other
 * than the one of the path is {@link Malformed}, whose message never echoes the text.
 */
public final class BotLinesCursor {

    static final byte VERSION = 0x01;
    static final int LENGTH = 44;
    private static final int DIGEST = 16;
    private static final int BYTES = 1 + 16 + DIGEST;
    private static final Pattern ALPHABET = Pattern.compile("^[A-Za-z0-9_-]{44}$");

    private BotLinesCursor() {}

    /** The cursor is refused before any read; the message is fixed and never carries the text. */
    public static final class Malformed extends RealPnlLineCursorRejected {
        private static final long serialVersionUID = 1L;

        Malformed(String reason) {
            super(reason);
        }
    }

    /** The anchor of a page: the bot of the path and the digest of the identity of the last line read. */
    public record Anchor(UUID botId, byte[] digest) {

        public Anchor {
            Objects.requireNonNull(botId, "botId");
            digest = digest.clone();
        }

        @Override
        public byte[] digest() {
            return digest.clone();
        }

        /** True for the line of this identity (constant time, though the digest is no secret). */
        public boolean matches(String identity, RealPnlLineOrigin origin) {
            return MessageDigest.isEqual(digest, BotLinesCursor.digest(identity, origin));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Anchor that && botId.equals(that.botId) && Arrays.equals(digest, that.digest);
        }

        @Override
        public int hashCode() {
            return 31 * botId.hashCode() + Arrays.hashCode(digest);
        }

        @Override
        public String toString() {
            return "Anchor[bot=" + botId + "]";
        }
    }

    /** The cursor of the line {@code (identity, origin)} of the bot. */
    public static String encode(UUID botId, String identity, RealPnlLineOrigin origin) {
        ByteBuffer raw = ByteBuffer.allocate(BYTES);
        raw.put(VERSION);
        raw.putLong(botId.getMostSignificantBits());
        raw.putLong(botId.getLeastSignificantBits());
        raw.put(digest(identity, origin));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.array());
    }

    /**
     * The anchor of a cursor sent for the bot {@code botId} of the path.
     *
     * @throws Malformed with a fixed reason, never the text
     */
    public static Anchor parse(String text, UUID botId) {
        if (text == null || text.length() != LENGTH) {
            throw new Malformed("The cursor has not the expected length.");
        }
        if (!ALPHABET.matcher(text).matches()) {
            throw new Malformed("The cursor has a character outside its alphabet.");
        }
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(text);
        } catch (IllegalArgumentException notBase64) {
            throw new Malformed("The cursor is not readable.");
        }
        if (raw.length != BYTES || raw[0] != VERSION) {
            throw new Malformed("The cursor is of another version.");
        }
        ByteBuffer buffer = ByteBuffer.wrap(raw, 1, 16);
        UUID bot = new UUID(buffer.getLong(), buffer.getLong());
        if (!bot.equals(botId)) {
            throw new Malformed("The cursor is of another bot.");
        }
        return new Anchor(bot, Arrays.copyOfRange(raw, 17, BYTES));
    }

    static byte[] digest(String identity, RealPnlLineOrigin origin) {
        try {
            byte[] full = MessageDigest.getInstance("SHA-256")
                    .digest(("pnl-line:" + identity + "|" + origin.name()).getBytes(StandardCharsets.UTF_8));
            return Arrays.copyOf(full, DIGEST);
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", missing);
        }
    }
}
