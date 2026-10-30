package com.memetrade.audit.domain;

import java.util.List;

/**
 * Read port of the trail for the administrator query (BR2.3, NFR3.1): rows ordered by {@code occurredAt} then
 * {@code eventId}, both descending, starting strictly after the cursor, without OFFSET.
 */
public interface AuditEventReader {

    /** At most {@code rows} events of the query, in the order of BR2.3. */
    List<AuditEventRecord> read(AuditEventQuery query, int rows);
}
