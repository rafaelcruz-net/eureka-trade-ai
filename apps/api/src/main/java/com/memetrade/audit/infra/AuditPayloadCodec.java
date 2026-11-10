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

/**
 * Serialises {@link AuditEventCommand} into the {@code audit_outbox.payload} JSON document and back.
 *
 * <p>u2-retention (BR2.1, BR2.18): the wallet of the command is never written; its HMAC goes as the top-level field
 * {@code walletHmac}, which {@link #decode(String)} returns next to the event. A payload without the field (older events,
 * events without a wallet) decodes with an empty HMAC; unknown fields are ignored.
 */
final class AuditPayloadCodec {

    static final String WALLET_HMAC = "walletHmac";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AuditPayloadCodec() {}

    /** An event without a wallet reference. */
    static String encode(AuditEventCommand event) {
        return encode(event, Optional.empty());
    }

    static String encode(AuditEventCommand event, Optional<String> walletHmac) {
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
        walletHmac.ifPresent(hmac -> node.put(WALLET_HMAC, hmac));
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

    static DecodedAuditEvent decode(String payload) {
        try {
            JsonNode node = MAPPER.readTree(payload);
            Map<String, String> details = new LinkedHashMap<>();
            node.path("details")
                    .fields()
                    .forEachRemaining(e -> details.put(e.getKey(), e.getValue().asText()));
            JsonNode tenant = node.get("tenantId");
            JsonNode target = node.get("targetRef");
            JsonNode hmac = node.get(WALLET_HMAC);
            AuditEventCommand command = new AuditEventCommand(
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
            return new DecodedAuditEvent(
                    command, hmac == null || hmac.isNull() ? Optional.empty() : Optional.of(hmac.asText()));
        } catch (JsonProcessingException corrupt) {
            throw new IllegalStateException("Audit outbox payload is not valid JSON", corrupt);
        }
    }
}
