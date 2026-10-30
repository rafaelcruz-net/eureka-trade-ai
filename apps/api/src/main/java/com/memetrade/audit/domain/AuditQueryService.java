package com.memetrade.audit.domain;

import com.memetrade.audit.api.AuditActions;
import com.memetrade.audit.api.AuditEventCommand;
import com.memetrade.audit.api.AuditEventPublisher;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administrator query of the trail (F3). The page is read and {@code AUDIT_QUERIED} is published in the SAME
 * read-write transaction (BR2.5, NFR7.5): when the event cannot be written the transaction rolls back and no page
 * leaves the server, so no query returns events without leaving a trace. The event carries the filters, never the
 * returned items.
 */
public class AuditQueryService {

    public static final String ROUTE = "GET /api/v1/admin/audit-events";

    private final AuditEventReader reader;
    private final AuditEventPublisher audit;
    private final Clock clock;

    public AuditQueryService(AuditEventReader reader, AuditEventPublisher audit, Clock clock) {
        this.reader = reader;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public AuditEventPage query(AuditEventQuery query, UUID administratorId, String correlationId) {
        AuditEventPage page = AuditEventPage.of(reader.read(query, query.rowsToRead()), query.limit());
        Map<String, String> filters = new LinkedHashMap<>();
        filters.put("from", query.from().toString());
        filters.put("to", query.to().toString());
        query.action().ifPresent(action -> filters.put("action", action));
        audit.publish(AuditEventCommand.admin(
                administratorId, AuditActions.AUDIT_QUERIED, ROUTE, correlationId, clock.instant(), filters));
        return page;
    }
}
