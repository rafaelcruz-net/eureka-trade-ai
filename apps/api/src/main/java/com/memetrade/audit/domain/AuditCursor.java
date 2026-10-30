package com.memetrade.audit.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.shared.error.ApiException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * Opaque cursor of the trail query (BR2.3): {@code occurredAt} and {@code eventId} of the last item of a page, in
 * Base64 URL. The next page starts strictly after that pair. Not signed: only an administrator queries, and a
 * tampered cursor only moves the start of the page (security-design.md, residual risks).
 */
public record AuditCursor(Instant occurredAt, UUID eventId) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public AuditCursor {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(eventId, "eventId");
    }

    public String encode() {
        String json = JSON.createObjectNode()
                .put("o", occurredAt.toString())
                .put("e", eventId.toString())
                .toString();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** @throws ApiException {@code 400 VALIDATION_FAILED} on {@code cursor} when the text is not a cursor */
    public static AuditCursor decode(String text) {
        try {
            JsonNode node = JSON.readTree(Base64.getUrlDecoder().decode(text));
            if (node == null
                    || !node.isObject()
                    || node.size() != 2
                    || !node.path("o").isTextual()
                    || !node.path("e").isTextual()) {
                throw invalid();
            }
            return new AuditCursor(
                    Instant.parse(node.get("o").asText()),
                    UUID.fromString(node.get("e").asText()));
        } catch (ApiException rethrown) {
            throw rethrown;
        } catch (RuntimeException | IOException malformed) {
            throw invalid();
        }
    }

    static ApiException invalid() {
        return ApiException.validation("cursor", "Cursor is not valid; start again from the first page.");
    }
}
