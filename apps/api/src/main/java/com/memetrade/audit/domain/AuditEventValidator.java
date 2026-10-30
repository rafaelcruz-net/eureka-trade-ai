package com.memetrade.audit.domain;

import com.memetrade.audit.api.ActorType;
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

    private static final String BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final BigInteger FIFTY_EIGHT = BigInteger.valueOf(58);
    private static final int SIGNATURE_BYTES = 64;

    /** @throws AuditPublishRejectedException at the first problem found */
    public void validate(AuditEventCommand event) {
        if (!ACTION.matcher(event.action()).matches()) {
            throw new AuditPublishRejectedException("action", Reason.INVALID_FORMAT);
        }
        if (event.actorType() == ActorType.PERSON && event.tenantId().isEmpty()) {
            throw new AuditPublishRejectedException("tenantId", Reason.MISSING_FIELD);
        }
        requireLength("actor", event.actor(), MAX_ACTOR);
        requireLength("correlationId", event.correlationId(), MAX_CORRELATION_ID);
        if (event.targetRef() != null) {
            requireLength("targetRef", event.targetRef(), MAX_TARGET_REF);
            requireAllowedValue("targetRef", event.targetRef());
        }
        validateDetails(event.details());
    }

    /** BR1.4 over every key and value of {@code details}. */
    public void validateDetails(Map<String, String> details) {
        for (Map.Entry<String, String> entry : details.entrySet()) {
            if (FORBIDDEN_KEYS.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                throw new AuditPublishRejectedException("details." + entry.getKey(), Reason.FORBIDDEN_KEY);
            }
            requireAllowedValue("details." + entry.getKey(), entry.getValue());
        }
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
