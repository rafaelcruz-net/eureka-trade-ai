package com.memetrade.audit.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.memetrade.audit.api.ActorType;
import com.memetrade.audit.api.AuditEventCommand;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Serialises {@link AuditEventCommand} into the {@code audit_outbox.payload} JSON document and back. */
final class AuditPayloadCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AuditPayloadCodec() {}

    static String encode(AuditEventCommand event) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("eventId", event.eventId().toString());
        event.tenantId().ifPresentOrElse(t -> node.put("tenantId", t.toString()), () -> node.putNull("tenantId"));
        node.put("actor", event.actor());
        node.put("actorType", event.actorType().name());
        node.put("action", event.action());
        if (event.targetRef() == null) {
            node.putNull("targetRef");
        } else {
            node.put("targetRef", event.targetRef());
        }
        node.put("correlationId", event.correlationId());
        node.put("occurredAt", event.occurredAt().toString());
        ObjectNode details = node.putObject("details");
        event.details().forEach(details::put);
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Audit payload could not be serialised", impossible);
        }
    }

    static String encodeDetails(AuditEventCommand event) {
        try {
            return MAPPER.writeValueAsString(event.details());
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Audit details could not be serialised", impossible);
        }
    }

    static AuditEventCommand decode(String payload) {
        try {
            JsonNode node = MAPPER.readTree(payload);
            Map<String, String> details = new LinkedHashMap<>();
            node.path("details")
                    .fields()
                    .forEachRemaining(e -> details.put(e.getKey(), e.getValue().asText()));
            JsonNode tenant = node.get("tenantId");
            JsonNode target = node.get("targetRef");
            return new AuditEventCommand(
                    UUID.fromString(node.get("eventId").asText()),
                    tenant == null || tenant.isNull()
                            ? Optional.empty()
                            : Optional.of(UUID.fromString(tenant.asText())),
                    node.get("actor").asText(),
                    ActorType.valueOf(node.get("actorType").asText()),
                    node.get("action").asText(),
                    target == null || target.isNull() ? null : target.asText(),
                    node.get("correlationId").asText(),
                    Instant.parse(node.get("occurredAt").asText()),
                    details);
        } catch (JsonProcessingException corrupt) {
            throw new IllegalStateException("Audit outbox payload is not valid JSON", corrupt);
        }
    }
}
