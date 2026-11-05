package com.memetrade.riskcontrol.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Port of the {@code order_rejection} and {@code rejection_reason_detail} tables (implemented by the infrastructure).
 * Every read filters by tenant, person and PAPER; no method changes or deletes a row.
 */
public interface RejectionRepository {

    /** BR3.5: the rejection and its details, in the caller's transaction (details in one batch). */
    void insert(RejectionRecord rejection);

    /**
     * Inserts many rejections and all their details in two batches, in the caller's transaction (the preparation
     * volume of the load scenario {@code u6}; the same rows the gate writes).
     */
    void insertAll(List<RejectionRecord> rejections);

    /**
     * BR4.2: one page of the person's rejections of the bot, newest first ({@code evaluated_at} then
     * {@code rejection_id}, both descending), after {@code after} when given; details of the page in one more query.
     */
    RejectionPage page(UUID tenantId, UUID ownerUserId, UUID botId, Optional<PageKey> after, int limit);

    /** The position of the last item of a page (keyset cursor). */
    record PageKey(Instant evaluatedAt, UUID rejectionId) {
        public PageKey {
            Objects.requireNonNull(evaluatedAt, "evaluatedAt");
            Objects.requireNonNull(rejectionId, "rejectionId");
        }
    }

    /**
     * @param total the count of the person's rejections of the bot, bounded by {@link #TOTAL_CAP}
     * @param totalCapped true when there are more than {@link #TOTAL_CAP}
     */
    record RejectionPage(List<RejectionRecord> items, Optional<PageKey> next, long total, boolean totalCapped) {

        /** performance-design.md: the total counts at most 10000 rows through the page index. */
        public static final long TOTAL_CAP = 10_000;

        public RejectionPage {
            items = List.copyOf(items);
            Objects.requireNonNull(next, "next");
        }
    }
}
