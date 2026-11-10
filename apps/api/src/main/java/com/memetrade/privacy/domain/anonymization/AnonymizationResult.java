package com.memetrade.privacy.domain.anonymization;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * entities.md AnonymizationResult (P2; BR5.2, BR5.5): what the anonymization did, only internal identifiers, the
 * instant and counts (NFR1.2). A repetition on an account already ANONYMIZED answers the same request, person and instant
 * with {@code alreadyAnonymized} true and zero counts: nothing was done by that call.
 *
 * <p>It is also the answer stored by the idempotency mechanism, so the same key replays exactly the same result.
 */
public record AnonymizationResult(
        UUID requestId,
        UUID userId,
        Instant anonymizedAt,
        int sessionsRevoked,
        int botsStopped,
        int feedbackDeleted,
        boolean alreadyAnonymized) {

    private static final Pattern FIELD = Pattern.compile("\"([a-zA-Z]+)\"\\s*:\\s*(\"([^\"]*)\"|true|false|-?\\d+)");

    public AnonymizationResult {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(anonymizedAt, "anonymizedAt");
        if (sessionsRevoked < 0 || botsStopped < 0 || feedbackDeleted < 0) {
            throw new IllegalArgumentException("The counts of an anonymization are never negative");
        }
    }

    /** BR5.5: the answer to a repetition; nothing was changed by it. */
    public static AnonymizationResult repetition(UUID requestId, UUID userId, Instant anonymizedAt) {
        return new AnonymizationResult(requestId, userId, anonymizedAt, 0, 0, 0, true);
    }

    /** The stored JSON answer (idempotency): identifiers, instant and counts only. */
    public String toStored() {
        return "{\"requestId\":\"" + requestId + "\",\"userId\":\"" + userId + "\",\"anonymizedAt\":\""
                + anonymizedAt + "\",\"sessionsRevoked\":" + sessionsRevoked + ",\"botsStopped\":" + botsStopped
                + ",\"feedbackDeleted\":" + feedbackDeleted + ",\"alreadyAnonymized\":" + alreadyAnonymized + "}";
    }

    /**
     * Reads a stored answer back (the database may normalise the JSON: key order and spaces).
     *
     * @throws IllegalStateException when the body is not a stored anonymization answer
     */
    public static AnonymizationResult fromStored(String body) {
        java.util.Map<String, String> values = new java.util.HashMap<>();
        Matcher matcher = FIELD.matcher(body == null ? "" : body);
        while (matcher.find()) {
            values.put(matcher.group(1), matcher.group(3) != null ? matcher.group(3) : matcher.group(2));
        }
        try {
            return new AnonymizationResult(
                    UUID.fromString(required(values, "requestId")),
                    UUID.fromString(required(values, "userId")),
                    Instant.parse(required(values, "anonymizedAt")),
                    Integer.parseInt(required(values, "sessionsRevoked")),
                    Integer.parseInt(required(values, "botsStopped")),
                    Integer.parseInt(required(values, "feedbackDeleted")),
                    Boolean.parseBoolean(required(values, "alreadyAnonymized")));
        } catch (IllegalArgumentException | DateTimeParseException malformed) {
            throw new IllegalStateException("A stored anonymization answer could not be read", malformed);
        }
    }

    private static String required(java.util.Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null) {
            throw new IllegalStateException("A stored anonymization answer misses " + name);
        }
        return value;
    }
}
