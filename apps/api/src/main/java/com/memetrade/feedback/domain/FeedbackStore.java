package com.memetrade.feedback.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Persistence port of {@code feedback_entry} (the purge uses its own retention pool, not this port). */
public interface FeedbackStore {

    /**
     * Serialises the submissions of one person until the end of the caller's transaction (NFR4.3), so the count of
     * {@link #createdSince} and the insert of two simultaneous submissions never overlap.
     */
    void lockPerson(UUID userId);

    /** The instants of the person's comments with {@code createdAt > since}, oldest first. */
    List<Instant> createdSince(UUID userId, Instant since);

    void insert(FeedbackEntry entry);

    /** One page newest first ({@code createdAt}, then {@code feedbackId}, both descending), after {@code after}. */
    List<FeedbackEntry> page(Optional<PageKey> after, int limit);

    /** The position of the last item of a page (keyset cursor). */
    record PageKey(Instant createdAt, UUID feedbackId) {
        public PageKey {
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(feedbackId, "feedbackId");
        }
    }
}
