package com.memetrade.audit.domain;

import com.memetrade.audit.api.ActorType;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One event of the trail as the administrator query returns it (BR2.4): every tenant and the events without tenant,
 * {@code actor} as recorded (masked before login) and {@code details} as recorded, including a full wallet address.
 * {@code recordedAt} is empty only for an event written by a U1 relay after a rollback of the API.
 */
public record AuditEventRecord(
        UUID eventId,
        Optional<UUID> tenantId,
        String actor,
        ActorType actorType,
        String action,
        String targetRef,
        String correlationId,
        Instant occurredAt,
        Optional<Instant> recordedAt,
        Map<String, String> details) {

    public AuditEventRecord {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(recordedAt, "recordedAt");
        details = Map.copyOf(details == null ? Map.of() : details);
    }

    /** Position of this event for the cursor of the next page. */
    public AuditCursor position() {
        return new AuditCursor(occurredAt, eventId);
    }
}
