package com.memetrade.testsupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * u4-data-requests (Passo 6): the routes P1 and P2 as the two apps call them, each POST with an Idempotency-Key. Text,
 * contact and signature are synthetic values of the test (never real personal data).
 */
public final class DataRequestCalls {

    public static final String GUEST_ROUTE = "/api/v1/privacy/requests";
    public static final String ADMIN_ROUTE = "/api/v1/admin/privacy/requests";

    private final MockMvc mvc;
    private final ObjectMapper json;

    public DataRequestCalls(MockMvc mvc, ObjectMapper json) {
        this.mvc = mvc;
        this.json = json;
    }

    // ------------------------------------------------------------------ P1

    public MockHttpServletResponse create(String bearer, String type, String details) throws Exception {
        return create(bearer, type, details, UUID.randomUUID());
    }

    public MockHttpServletResponse create(String bearer, String type, String details, UUID key) throws Exception {
        ObjectNode body = json.createObjectNode().put("type", type).put("details", details);
        return createBody(bearer, json.writeValueAsString(body), key);
    }

    public MockHttpServletResponse createBody(String bearer, String body, UUID key) throws Exception {
        return send(post(GUEST_ROUTE).content(body), bearer, key);
    }

    public MockHttpServletResponse list(String bearer) throws Exception {
        return send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(GUEST_ROUTE), bearer, null);
    }

    public MockHttpServletResponse get(String bearer, String requestId) throws Exception {
        return send(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(GUEST_ROUTE + "/" + requestId),
                bearer,
                null);
    }

    // ------------------------------------------------------------------ P2

    public MockHttpServletResponse adminList(String bearer, String query) throws Exception {
        return send(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        ADMIN_ROUTE + (query == null ? "" : "?" + query)),
                bearer,
                null);
    }

    public MockHttpServletResponse adminGet(String bearer, String requestId) throws Exception {
        return send(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(ADMIN_ROUTE + "/" + requestId),
                bearer,
                null);
    }

    public MockHttpServletResponse register(
            String bearer, String type, String details, String contact, String receivedAt, UUID userId)
            throws Exception {
        ObjectNode body = json.createObjectNode()
                .put("type", type)
                .put("details", details)
                .put("contact", contact)
                .put("receivedAt", receivedAt);
        if (userId != null) {
            body.put("userId", userId.toString());
        }
        return send(post(ADMIN_ROUTE).content(json.writeValueAsString(body)), bearer, UUID.randomUUID());
    }

    public MockHttpServletResponse transition(
            String bearer, String requestId, String to, String response, String reason, boolean release)
            throws Exception {
        ObjectNode body = json.createObjectNode().put("to", to).put("releaseAccessSummary", release);
        if (response != null) {
            body.put("response", response);
        }
        if (reason != null) {
            body.put("refusalReason", reason);
        }
        return send(
                post(ADMIN_ROUTE + "/" + requestId + "/transitions").content(json.writeValueAsString(body)),
                bearer,
                UUID.randomUUID());
    }

    public MockHttpServletResponse startVerification(String bearer, String requestId) throws Exception {
        return send(post(ADMIN_ROUTE + "/" + requestId + "/verification"), bearer, UUID.randomUUID());
    }

    public MockHttpServletResponse confirm(String bearer, String requestId, String signature) throws Exception {
        ObjectNode body = json.createObjectNode().put("signature", signature);
        return send(
                post(ADMIN_ROUTE + "/" + requestId + "/verification/confirm").content(json.writeValueAsString(body)),
                bearer,
                UUID.randomUUID());
    }

    public MockHttpServletResponse replySent(String bearer, String requestId) throws Exception {
        return send(post(ADMIN_ROUTE + "/" + requestId + "/reply-sent"), bearer, UUID.randomUUID());
    }

    /** u5-anonymization (P2 {@code adminAnonymizeAccount}): the confirmation as typed, with {@code key}. */
    public MockHttpServletResponse anonymize(String bearer, String requestId, String confirmation, UUID key)
            throws Exception {
        ObjectNode body = json.createObjectNode().put("confirmation", confirmation);
        return send(
                post(ADMIN_ROUTE + "/" + requestId + "/anonymization").content(json.writeValueAsString(body)),
                bearer,
                key);
    }

    public JsonNode body(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    private MockHttpServletResponse send(MockHttpServletRequestBuilder request, String bearer, UUID key)
            throws Exception {
        request.header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            request.header("Idempotency-Key", key.toString());
        }
        return mvc.perform(request).andReturn().getResponse();
    }
}
