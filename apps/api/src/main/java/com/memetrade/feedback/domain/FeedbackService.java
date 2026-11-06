package com.memetrade.feedback.domain;

import com.memetrade.identity.api.IdempotencyGuard;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.identity.api.IdempotencyRequest;
import com.memetrade.identity.api.IdempotencyScope;
import com.memetrade.identity.api.IdempotentOperation;
import com.memetrade.identity.api.IdempotentOutcome;
import com.memetrade.identity.api.IdempotentResponse;
import com.memetrade.identity.api.Principal;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.error.FieldViolation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Submission of a comment of a guest (BR6.1 to BR6.4, NFR4.2, NFR4.3), in ONE transaction:
 *
 * <ol>
 *   <li>the body is checked first (text of 1 to 2000 code points, not blank; screen S1 to S9), so a 400 is never stored;
 *   <li>the common idempotency mechanism claims the key (same key and body replay the 201; another body is 409);
 *   <li>the person is locked (submissions of one person are serialised), the comments of the last hour are counted and
 *       the 11th is {@code 429 RATE_LIMITED} with {@code Retry-After} (a 429 is not stored either: the failure releases
 *       the claim);
 *   <li>the comment is written with the tenant and the person of the token, the text exactly as sent.
 * </ol>
 *
 * The text never reaches a log, an event or a metric label; the metrics count by screen and refusal reason only.
 */
public class FeedbackService {

    /** BR6.1: at most 2000 characters, counted in code points. */
    public static final int MAX_TEXT = 2000;

    static final Duration WINDOW = Duration.ofHours(1);

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final FeedbackStore store;
    private final IdempotencyGuard idempotency;
    private final FeedbackEvents events;
    private final Clock clock;
    private final int maxPerHour;

    public FeedbackService(
            FeedbackStore store, IdempotencyGuard idempotency, FeedbackEvents events, Clock clock, int maxPerHour) {
        this.store = Objects.requireNonNull(store, "store");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxPerHour < 1) {
            throw new IllegalArgumentException("maxPerHour must be positive");
        }
        this.maxPerHour = maxPerHour;
    }

    @Transactional
    public Receipt submit(Submission submission) {
        FeedbackScreen screen = validate(submission);
        IdempotentResponse response;
        try {
            response = idempotency.execute(request(submission), () -> {
                FeedbackEntry entry = write(submission, screen);
                return IdempotentOutcome.of(IdempotentResponse.fresh(201, stored(entry)));
            });
        } catch (ApiException refused) {
            if (refused.code() == ErrorCode.IDEMPOTENCY_CONFLICT) {
                events.refused(FeedbackEvents.Refusal.IDEMPOTENCY_CONFLICT);
            }
            throw refused;
        }
        Receipt receipt = read(response.body(), response.replayed());
        if (!receipt.replayed()) {
            events.received(screen);
        }
        return receipt;
    }

    private FeedbackEntry write(Submission submission, FeedbackScreen screen) {
        Principal person = submission.person();
        store.lockPerson(person.userId());
        Instant now = clock.instant();
        List<Instant> lastHour = store.createdSince(person.userId(), now.minus(WINDOW));
        if (lastHour.size() >= maxPerHour) {
            events.refused(FeedbackEvents.Refusal.RATE_LIMITED);
            throw rateLimited(lastHour.getFirst(), now);
        }
        FeedbackEntry entry = new FeedbackEntry(
                UUID.randomUUID(), person.tenantId(), person.userId(), screen, submission.text(), now);
        store.insert(entry);
        return entry;
    }

    /** BR6.1: every refused field at once. */
    private FeedbackScreen validate(Submission submission) {
        List<FieldViolation> violations = new ArrayList<>();
        String text = submission.text();
        if (text == null || text.isBlank()) {
            violations.add(new FieldViolation("text", "Must not be empty."));
        } else if (text.codePointCount(0, text.length()) > MAX_TEXT) {
            violations.add(new FieldViolation("text", "Must have at most 2000 characters."));
        }
        Optional<FeedbackScreen> screen = FeedbackScreen.parse(submission.screen());
        if (screen.isEmpty()) {
            violations.add(new FieldViolation("screen", "Must be one of S1 to S9."));
        }
        if (!violations.isEmpty()) {
            events.refused(FeedbackEvents.Refusal.VALIDATION);
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "One or more fields are invalid.", violations, null, Map.of());
        }
        return screen.get();
    }

    /** Retry-After: until the oldest comment of the window leaves it, at least 1 second. */
    private static ApiException rateLimited(Instant oldest, Instant now) {
        long seconds = Math.max(1, Duration.between(now, oldest.plus(WINDOW)).toSeconds());
        return new ApiException(
                ErrorCode.RATE_LIMITED,
                "Too many comments in the last hour; try again later.",
                List.of(),
                "FEEDBACK_LIMIT",
                Map.of("Retry-After", Long.toString(seconds)));
    }

    private static IdempotencyRequest request(Submission submission) {
        return new IdempotencyRequest(
                IdempotentOperation.FEEDBACK_SUBMIT,
                IdempotencyScope.authenticated(submission.person(), IdempotentOperation.FEEDBACK_SUBMIT),
                submission.key(),
                digest(submission.screen() + "\n" + submission.text()));
    }

    /** The stored answer is the identifier and the instant, never the text. */
    private static String stored(FeedbackEntry entry) {
        return "{\"feedbackId\":\"" + entry.feedbackId() + "\",\"createdAt\":\"" + entry.createdAt() + "\"}";
    }

    /** The stored body may come back normalised by the database (key order, spaces): read it as JSON. */
    private static Receipt read(String body, boolean replayed) {
        try {
            com.fasterxml.jackson.databind.JsonNode node = JSON.readTree(body);
            return new Receipt(
                    UUID.fromString(node.path("feedbackId").asText()),
                    Instant.parse(node.path("createdAt").asText()),
                    replayed);
        } catch (com.fasterxml.jackson.core.JsonProcessingException | RuntimeException unreadable) {
            throw new IllegalStateException("A stored feedback answer could not be read", unreadable);
        }
    }

    private static String digest(String content) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", impossible);
        }
    }

    /** One submission: the person of the token, the raw screen and text, the Idempotency-Key. */
    public record Submission(Principal person, String screen, String text, IdempotencyKey key) {

        public Submission {
            Objects.requireNonNull(person, "person");
            Objects.requireNonNull(key, "key");
        }

        @Override
        public String toString() {
            // The text never reaches a log (NFR6.7).
            return "Submission[screen=" + screen + "]";
        }
    }

    /** The answer of a 201; {@code replayed} when the Idempotency-Key answered a previous submission. */
    public record Receipt(UUID feedbackId, Instant createdAt, boolean replayed) {}
}
