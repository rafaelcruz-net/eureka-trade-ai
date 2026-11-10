package com.memetrade.privacy.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The versioned list of the catalog rows whose purge arrives in a later Unit (entities.md PendingRetention, BR2.12). It
 * started with {@code DATA_SUBJECT_REQUESTS} only; u4-data-requests delivered that purge
 * ({@code dataSubjectRequestPurge}, BR4.15) and removed the row in the same change, so the list is empty. The rows
 * "while the account exists" are GUARD and stay out: their end action (anonymisation) arrives with u5-anonymization, and
 * the check compares the period, not the action. The Unit that delivers a purge removes its row here and declares the
 * purge in the same commit; no row enters without a recorded decision.
 */
public final class PendingRetention {

    /**
     * One pending row.
     *
     * @param expectedPurgeId the id the purge will declare (J7); its declaration appearing while the row is still here
     *     is a divergence (BR2.12)
     */
    public record Entry(String ruleId, String plannedUnit, String expectedPurgeId) {
        public Entry {
            Objects.requireNonNull(ruleId, "ruleId");
            Objects.requireNonNull(plannedUnit, "plannedUnit");
            Objects.requireNonNull(expectedPurgeId, "expectedPurgeId");
        }
    }

    private static final List<Entry> ENTRIES = List.of();

    private PendingRetention() {}

    public static List<Entry> entries() {
        return ENTRIES;
    }

    public static Optional<Entry> find(String ruleId) {
        return ENTRIES.stream().filter(entry -> entry.ruleId().equals(ruleId)).findFirst();
    }
}
