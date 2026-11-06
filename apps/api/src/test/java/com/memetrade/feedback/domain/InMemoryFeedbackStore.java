package com.memetrade.feedback.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Unit-test double of {@link FeedbackStore} that also records the order of the calls (lock before count). */
public final class InMemoryFeedbackStore implements FeedbackStore {

    private final List<FeedbackEntry> entries = new ArrayList<>();
    private final List<String> calls = new ArrayList<>();

    @Override
    public synchronized void lockPerson(UUID userId) {
        calls.add("lock");
    }

    @Override
    public synchronized List<Instant> createdSince(UUID userId, Instant since) {
        calls.add("count");
        return entries.stream()
                .filter(entry ->
                        entry.userId().equals(userId) && entry.createdAt().isAfter(since))
                .map(FeedbackEntry::createdAt)
                .sorted()
                .toList();
    }

    @Override
    public synchronized void insert(FeedbackEntry entry) {
        calls.add("insert");
        entries.add(entry);
    }

    @Override
    public synchronized List<FeedbackEntry> page(Optional<PageKey> after, int limit) {
        Comparator<FeedbackEntry> newestFirst = Comparator.comparing(FeedbackEntry::createdAt)
                .thenComparing(FeedbackEntry::feedbackId)
                .reversed();
        return entries.stream()
                .sorted(newestFirst)
                .filter(entry -> after.isEmpty()
                        || entry.createdAt().isBefore(after.get().createdAt())
                        || (entry.createdAt().equals(after.get().createdAt())
                                && entry.feedbackId().compareTo(after.get().feedbackId()) < 0))
                .limit(limit)
                .toList();
    }

    public synchronized List<FeedbackEntry> entries() {
        return List.copyOf(entries);
    }

    public synchronized List<String> calls() {
        return List.copyOf(calls);
    }
}
