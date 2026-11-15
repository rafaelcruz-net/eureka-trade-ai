package com.memetrade.txsigner.infra.credential;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;

/**
 * Service credential of the TxSigner (NFR1.5): the current and the previous token are both accepted, so a rotation
 * with 24 h of overlap needs no restart. Tokens are compared by their SHA-256 in constant time; an empty token is
 * never accepted, so a TxSigner started without a credential refuses every call.
 */
public interface ServiceCredentials {

    /** The accepted tokens right now (current first). */
    List<String> acceptedTokens();

    /** Age in days of the current credential, for the alert at 80 days; -1 when unknown. */
    default long currentAgeDays() {
        return -1;
    }

    default boolean accepts(String presented) {
        if (presented == null || presented.isBlank()) {
            return false;
        }
        byte[] candidate = sha256(presented);
        boolean accepted = false;
        for (String token : acceptedTokens()) {
            if (token != null && !token.isBlank()) {
                accepted |= MessageDigest.isEqual(sha256(token), candidate);
            }
        }
        return accepted;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(Objects.requireNonNull(value).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
    }
}
