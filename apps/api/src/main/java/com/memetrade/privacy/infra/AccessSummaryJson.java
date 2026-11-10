package com.memetrade.privacy.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.memetrade.identity.api.PersonDataPort.AcceptedVersion;
import com.memetrade.privacy.domain.request.AccessSummary;
import com.memetrade.privacy.domain.request.DataRequestStatus;
import com.memetrade.privacy.domain.request.DataRequestType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The snapshot of an Access summary in {@code data_subject_request.access_summary} (jsonb, BR4.8): the same fields as
 * the P1 {@code AccessSummary}, instants as ISO-8601 UTC strings. Never a full wallet (the summary carries it masked).
 */
final class AccessSummaryJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private AccessSummaryJson() {}

    static String write(AccessSummary summary) {
        ObjectNode root = JSON.createObjectNode()
                .put("walletMasked", summary.walletMasked())
                .put("provider", summary.provider())
                .put("accountCreatedAt", summary.accountCreatedAt().toString())
                .put("botCount", summary.botCount())
                .put("paperOrderCount", summary.paperOrderCount())
                .put("feedbackCount", summary.feedbackCount());
        summary.lastLoginAt()
                .ifPresentOrElse(at -> root.put("lastLoginAt", at.toString()), () -> root.putNull("lastLoginAt"));
        ArrayNode versions = root.putArray("acceptedVersions");
        summary.acceptedVersions().forEach(version -> versions.addObject()
                .put("version", version.version())
                .put("acceptedAt", version.acceptedAt().toString()));
        ArrayNode requests = root.putArray("requests");
        summary.requests().forEach(line -> requests.addObject()
                .put("type", line.type().name())
                .put("status", line.status().name())
                .put("receivedAt", line.receivedAt().toString()));
        return root.toString();
    }

    static AccessSummary read(String json) {
        try {
            JsonNode root = JSON.readTree(json);
            List<AcceptedVersion> versions = new ArrayList<>();
            root.path("acceptedVersions")
                    .forEach(node -> versions.add(new AcceptedVersion(
                            node.path("version").asText(),
                            Instant.parse(node.path("acceptedAt").asText()))));
            List<AccessSummary.RequestLine> requests = new ArrayList<>();
            root.path("requests")
                    .forEach(node -> requests.add(new AccessSummary.RequestLine(
                            DataRequestType.valueOf(node.path("type").asText()),
                            DataRequestStatus.valueOf(node.path("status").asText()),
                            Instant.parse(node.path("receivedAt").asText()))));
            JsonNode lastLogin = root.path("lastLoginAt");
            return new AccessSummary(
                    root.path("walletMasked").asText(),
                    root.path("provider").asText(),
                    Instant.parse(root.path("accountCreatedAt").asText()),
                    lastLogin.isTextual() ? Optional.of(Instant.parse(lastLogin.asText())) : Optional.empty(),
                    versions,
                    root.path("botCount").asLong(),
                    root.path("paperOrderCount").asLong(),
                    root.path("feedbackCount").asLong(),
                    requests);
        } catch (JsonProcessingException | RuntimeException unreadable) {
            throw new IllegalStateException("A stored Access summary could not be read", unreadable);
        }
    }
}
