package com.memetrade.shared.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.shared.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque tuple cursor (U7, NFR2.3): the instant and the id of the last item, plus the scope of the query it belongs to
 * (route and filters). A cursor that cannot be read, or that belongs to another query, is {@code 400
 * VALIDATION_FAILED} on {@code cursor}, like the cursors of the earlier Units.
 */
public record KeysetCursor(Instant at, UUID id) {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int VERSION = 1;

    public String encode(String scope) {
        String text = JSON.createObjectNode()
                .put("v", VERSION)
                .put("q", scope)
                .put("t", at.toString())
                .put("i", id.toString())
                .toString();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    public static KeysetCursor decode(String text, String scope) {
        try {
            JsonNode node = JSON.readTree(Base64.getUrlDecoder().decode(text));
            if (node == null
                    || node.path("v").asInt() != VERSION
                    || !scope.equals(node.path("q").asText())) {
                throw invalid();
            }
            return new KeysetCursor(
                    Instant.parse(node.path("t").asText()),
                    UUID.fromString(node.path("i").asText()));
        } catch (ApiException rethrown) {
            throw rethrown;
        } catch (RuntimeException | java.io.IOException malformed) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return ApiException.validation("cursor", "Invalid cursor.");
    }
}
