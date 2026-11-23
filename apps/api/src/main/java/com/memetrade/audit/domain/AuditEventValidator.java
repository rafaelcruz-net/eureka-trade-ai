package com.memetrade.audit.domain;

import com.memetrade.audit.api.ActorType;
import com.memetrade.audit.api.AuditActions;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditPublishRejectedException;
import com.memetrade.audit.api.AuditPublishRejectedException.Reason;
import java.math.BigInteger;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks an event before the publisher writes it (BR1.2, BR1.4, NFR7.2). Expressions are compiled once and
 * {@code details} is walked once; no database access. Required fields that cannot be null are already enforced by
 * the {@link AuditEventCommand} constructor (C9); this class adds format, coherence and forbidden content.
 * Exceptions name the field and the reason, never the value.
 */
public final class AuditEventValidator {

    /** Upper-case words joined by underscores, up to 64 characters (Q6 of the functional design). */
    static final Pattern ACTION = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    static final int MAX_ACTOR = 120;
    static final int MAX_TARGET_REF = 200;
    static final int MAX_CORRELATION_ID = 64;

    /** BR1.4 keys (case-insensitive) plus the secret-like keys the C9 type already refuses. */
    static final Set<String> FORBIDDEN_KEYS = Set.of(
            "nonce",
            "signature",
            "token",
            "accesstoken",
            "refreshtoken",
            "authorization",
            "password",
            "secret",
            "privatekey",
            "seedphrase",
            "mnemonic");

    /** Header, payload and signature of a JWS in compact form (the access token of the API is one). */
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{2,}\\.[A-Za-z0-9_-]{2,}\\.[A-Za-z0-9_-]*");

    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+\\S+");

    /** Candidates for a Base58 Ed25519 signature: 64 bytes encode to 86-88 characters (wallets are 32-44). */
    private static final Pattern BASE58_RUN = Pattern.compile("[1-9A-HJ-NP-Za-km-z]{80,90}");

    /**
     * u2-retention (BR2.3): the alphanumeric words of a value. A wallet candidate is a WHOLE word of 32 to 44 Base58
     * characters: a fragment of a longer word (a hexadecimal hash or digest whose run between two '0' characters happens
     * to be Base58) is not an address and must not refuse a legitimate event.
     */
    private static final Pattern ALPHANUMERIC_WORD = Pattern.compile("[0-9A-Za-z]+");

    private static final Pattern BASE58_WORD = Pattern.compile("^[1-9A-HJ-NP-Za-km-z]{32,44}$");

    private static final String BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final BigInteger FIFTY_EIGHT = BigInteger.valueOf(58);
    private static final int SIGNATURE_BYTES = 64;

    /** An Ed25519 public key, which is what a Solana wallet address encodes (BR2.3). */
    private static final int WALLET_BYTES = 32;

    /** @throws AuditPublishRejectedException at the first problem found */
    public void validate(AuditEventCommand event) {
        if (!ACTION.matcher(event.action()).matches()) {
            throw new AuditPublishRejectedException("action", Reason.INVALID_FORMAT);
        }
        if (event.actorType() == ActorType.PERSON && event.tenantId().isEmpty()) {
            throw new AuditPublishRejectedException("tenantId", Reason.MISSING_FIELD);
        }
        requireLength("actor", event.actor(), MAX_ACTOR);
        if (containsFullWallet(event.actor())) {
            throw new AuditPublishRejectedException("actor", Reason.FULL_WALLET);
        }
        requireLength("correlationId", event.correlationId(), MAX_CORRELATION_ID);
        if (event.targetRef() != null) {
            requireLength("targetRef", event.targetRef(), MAX_TARGET_REF);
            requireAllowedValue("targetRef", event.targetRef());
            if (containsFullWallet(event.targetRef())) {
                throw new AuditPublishRejectedException("targetRef", Reason.FULL_WALLET);
            }
        }
        validateDetails(event.details());
        validateReal(event.real());
        if (AuditActions.ACCOUNT_ANONYMIZED.equals(event.action())) {
            for (String key : event.details().keySet()) {
                if (!AuditActions.ACCOUNT_ANONYMIZED_DETAILS.contains(key)) {
                    // u5-anonymization (NFR8.2): only the three counts, never a wallet, a name or a text.
                    throw new AuditPublishRejectedException("details." + key, Reason.FORBIDDEN_KEY);
                }
            }
        }
    }

    /**
     * 261113-trading-real-bot (M8, BR6.2): the typed REAL fields accept exactly what their name says, a 64-byte Base58
     * transaction signature and 32-byte Base58 addresses; any other value is refused.
     */
    static void validateReal(com.memetrade.audit.api.RealReferences real) {
        if (real == null) {
            return;
        }
        if (real.txSignature() != null && !isBase58OfBytes(real.txSignature(), SIGNATURE_RUN, SIGNATURE_BYTES)) {
            throw new AuditPublishRejectedException("real.txSignature", Reason.INVALID_FORMAT);
        }
        if (real.mint() != null && !isBase58OfBytes(real.mint(), BASE58_WORD, WALLET_BYTES)) {
            throw new AuditPublishRejectedException("real.mint", Reason.INVALID_FORMAT);
        }
        if (real.botWalletAddress() != null && !isBase58OfBytes(real.botWalletAddress(), BASE58_WORD, WALLET_BYTES)) {
            throw new AuditPublishRejectedException("real.botWalletAddress", Reason.INVALID_FORMAT);
        }
    }

    private static final Pattern SIGNATURE_RUN = Pattern.compile("^[1-9A-HJ-NP-Za-km-z]{80,90}$");

    /**
     * 261121-paper-real-unico U2 (NFR3.9): true when {@code value} is a whole Base58 transaction signature of 64 bytes,
     * the same check as {@code real.txSignature}; false for anything else, null included.
     */
    public static boolean isTxSignature(String value) {
        return value != null && isBase58OfBytes(value, SIGNATURE_RUN, SIGNATURE_BYTES);
    }

    private static boolean isBase58OfBytes(String value, Pattern shape, int bytes) {
        return shape.matcher(value).matches() && decodedLength(value) == bytes;
    }

    /** BR1.4 and u2-retention BR2.3 over every key and value of {@code details}. */
    public void validateDetails(Map<String, String> details) {
        for (Map.Entry<String, String> entry : details.entrySet()) {
            String field = "details." + entry.getKey();
            if (FORBIDDEN_KEYS.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                throw new AuditPublishRejectedException(field, Reason.FORBIDDEN_KEY);
            }
            requireAllowedValue(field, entry.getValue());
            if (containsFullWallet(entry.getValue())) {
                throw new AuditPublishRejectedException(field, Reason.FULL_WALLET);
            }
        }
    }

    /**
     * u2-retention (BR2.3, NFR1.2): true when the text holds a whole word of 32 to 44 Base58 characters that decodes to
     * exactly 32 bytes, the size of an Ed25519 public key (a wallet address). The same decoding confirms the 64-byte
     * signatures; a UUID, a hexadecimal hash, an invitation code or a digest is not such a word.
     */
    public static boolean containsFullWallet(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        Matcher word = ALPHANUMERIC_WORD.matcher(text);
        while (word.find()) {
            String candidate = word.group();
            if (BASE58_WORD.matcher(candidate).matches() && decodedLength(candidate) == WALLET_BYTES) {
                return true;
            }
        }
        return false;
    }

    /** True when the text carries a JWT, a Bearer credential or a Base58 64-byte signature. */
    public static boolean containsForbiddenValue(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return JWT.matcher(text).find() || BEARER.matcher(text).find() || containsSignature(text);
    }

    /** True when {@code action} follows the upper-case-with-underscores format (safe as a metric label). */
    public static boolean isValidAction(String action) {
        return action != null && ACTION.matcher(action).matches();
    }

    private static void requireAllowedValue(String field, String value) {
        if (containsForbiddenValue(value)) {
            throw new AuditPublishRejectedException(field, Reason.FORBIDDEN_VALUE);
        }
    }

    private static void requireLength(String field, String value, int max) {
        if (value.length() > max) {
            throw new AuditPublishRejectedException(field, Reason.INVALID_FORMAT);
        }
    }

    private static boolean containsSignature(String text) {
        Matcher run = BASE58_RUN.matcher(text);
        while (run.find()) {
            if (decodedLength(run.group()) == SIGNATURE_BYTES) {
                return true;
            }
        }
        return false;
    }

    /** Number of bytes a Base58 text decodes to (leading '1' characters are leading zero bytes). */
    static int decodedLength(String base58) {
        int leadingZeros = 0;
        while (leadingZeros < base58.length() && base58.charAt(leadingZeros) == '1') {
            leadingZeros++;
        }
        BigInteger value = BigInteger.ZERO;
        for (int i = leadingZeros; i < base58.length(); i++) {
            value = value.multiply(FIFTY_EIGHT).add(BigInteger.valueOf(BASE58_ALPHABET.indexOf(base58.charAt(i))));
        }
        int significant = value.signum() == 0 ? 0 : (value.bitLength() + 7) / 8;
        return leadingZeros + significant;
    }
}
