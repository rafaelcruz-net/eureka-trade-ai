package com.memetrade.audit.domain;

import java.util.List;
import java.util.Optional;

/** One page of the trail query, most recent first, with the cursor of the next page (empty on the last page). */
public record AuditEventPage(List<AuditEventRecord> items, Optional<AuditCursor> nextCursor) {

    public AuditEventPage {
        items = List.copyOf(items);
    }

    /**
     * Builds the page from {@code limit + 1} rows read in order: the extra row only tells that a next page exists,
     * and the cursor points at the last returned item.
     */
    public static AuditEventPage of(List<AuditEventRecord> rows, int limit) {
        if (rows.size() <= limit) {
            return new AuditEventPage(rows, Optional.empty());
        }
        List<AuditEventRecord> page = rows.subList(0, limit);
        return new AuditEventPage(page, Optional.of(page.getLast().position()));
    }
}
