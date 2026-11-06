package com.memetrade.feedback.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A comment of a guest about the beta (entities.md FeedbackEntry, FR13.1). Tenant and person come from the token; the
 * text is kept exactly as sent. {@link #toString()} never shows the text (it never reaches a log, NFR6.7).
 */
public record FeedbackEntry(
        UUID feedbackId, UUID tenantId, UUID userId, FeedbackScreen screen, String text, Instant createdAt) {

    public FeedbackEntry {
        Objects.requireNonNull(feedbackId, "feedbackId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(screen, "screen");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    @Override
    public String toString() {
        return "FeedbackEntry[screen=" + screen + ", length=" + text.codePointCount(0, text.length()) + "]";
    }
}
