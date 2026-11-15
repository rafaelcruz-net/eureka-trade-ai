package com.memetrade.txsigner.policy;

import com.memetrade.txsigner.codec.Base58;
import com.memetrade.txsigner.codec.CodecException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The canonical consent message of contract-summary.md: ten {@code key: value} lines in a fixed order, separated by
 * LF, no trailing space nor extra line. Parsing is strict and the text must render back to exactly the same bytes,
 * so two different texts never carry the same consent.
 */
public record ConsentMessage(
        ConsentPurpose purpose,
        String network,
        String mainWallet,
        String botWallet,
        OperationMode mode,
        long capLamports,
        String nonce,
        Instant issuedAt,
        Instant expiresAt) {

    public static final String TITLE = "Eureka Trader - consentimento REAL v1";
    public static final int MAX_LENGTH = 1024;
    private static final int LINES = 10;
    private static final int NONCE_BYTES = 32;
    private static final Pattern LAMPORTS = Pattern.compile("^(0|[1-9][0-9]{0,19})$");
    private static final Pattern ADDRESS = Pattern.compile("^[1-9A-HJ-NP-Za-km-z]{32,44}$");
    private static final String[] KEYS = {
        "purpose: ",
        "network: ",
        "main-wallet: ",
        "bot-wallet: ",
        "mode: ",
        "cap-lamports: ",
        "nonce: ",
        "issued-at: ",
        "expires-at: "
    };

    public String render() {
        return String.join(
                "\n",
                TITLE,
                KEYS[0] + purpose,
                KEYS[1] + network,
                KEYS[2] + mainWallet,
                KEYS[3] + botWallet,
                KEYS[4] + mode,
                KEYS[5] + capLamports,
                KEYS[6] + nonce,
                KEYS[7] + issuedAt,
                KEYS[8] + expiresAt);
    }

    /** Parses a received text; empty when it is not exactly a canonical consent message. */
    public static Optional<ConsentMessage> parse(String text) {
        if (text == null || text.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        String[] lines = text.split("\n", -1);
        if (lines.length != LINES || !TITLE.equals(lines[0])) {
            return Optional.empty();
        }
        String[] values = new String[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) {
            String line = lines[i + 1];
            if (!line.startsWith(KEYS[i]) || line.length() == KEYS[i].length()) {
                return Optional.empty();
            }
            values[i] = line.substring(KEYS[i].length());
        }
        try {
            ConsentMessage message = new ConsentMessage(
                    ConsentPurpose.valueOf(values[0]),
                    values[1],
                    address(values[2]),
                    botWallet(values[3]),
                    OperationMode.valueOf(values[4]),
                    lamports(values[5]),
                    nonce(values[6]),
                    Instant.parse(values[7]),
                    Instant.parse(values[8]));
            return message.render().equals(text) ? Optional.of(message) : Optional.empty();
        } catch (IllegalArgumentException | DateTimeParseException | CodecException notCanonical) {
            return Optional.empty();
        }
    }

    private static String address(String value) {
        if (!ADDRESS.matcher(value).matches()) {
            throw new IllegalArgumentException("not an address");
        }
        Base58.decodeExact(value, 32);
        return value;
    }

    private static String botWallet(String value) {
        return ConsentCheck.NEW_WALLET.equals(value) ? value : address(value);
    }

    private static long lamports(String value) {
        if (!LAMPORTS.matcher(value).matches()) {
            throw new IllegalArgumentException("not a lamport amount");
        }
        return Long.parseLong(value);
    }

    private static String nonce(String value) {
        Base58.decodeExact(value, NONCE_BYTES);
        return value;
    }
}
