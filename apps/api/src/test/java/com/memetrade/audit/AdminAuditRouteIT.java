package com.memetrade.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.audit.infra.AuditOutboxRelay;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Negative tests of the administrative route {@code GET /api/v1/admin/audit-events} (BR2.1, NFR6.1): no token is
 * {@code 401}; a guest token is {@code 403 FORBIDDEN_ROLE}, never the data, with exactly one {@code REQUEST_FORBIDDEN}
 * per refusal. Referenced by the route registry as the route's negative test.
 */
@Tag("u2")
class AdminAuditRouteIT extends AbstractPostgresIT {

    private static final String ROUTE = "/api/v1/admin/audit-events?from=2026-01-01T00:00:00Z&to=2026-01-02T00:00:00Z";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private AuditOutboxRelay relay;

    private AuthFlow auth;

    @BeforeEach
    void setUp() {
        auth = new AuthFlow(mvc, json);
    }

    @Test
    void aGuestTokenIsRefusedWith403AndOneRequestForbiddenEvent() throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.betaUser(jdbc, wallet.address());
        String accessToken = auth.accessToken(auth.login(wallet));

        MockHttpServletResponse response = mvc.perform(get(ROUTE).header("Authorization", "Bearer " + accessToken))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("FORBIDDEN_ROLE").doesNotContain("\"items\"");
        while (relay.relayBatch() > 0) {
            // other test classes may have left rows pending; deliver every one, this event included
        }
        assertThat(jdbc.sql("select action from audit_event where correlation_id = :c")
                        .param("c", response.getHeader("X-Correlation-Id"))
                        .query(String.class)
                        .list())
                .containsExactly("REQUEST_FORBIDDEN");
    }

    @Test
    void twoGuestRefusalsRecordTwoEvents() throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.betaUser(jdbc, wallet.address());
        String accessToken = auth.accessToken(auth.login(wallet));

        String first = refusedCorrelation(accessToken);
        String second = refusedCorrelation(accessToken);
        while (relay.relayBatch() > 0) {
            // other test classes may have left rows pending; deliver every one, this event included
        }

        assertThat(jdbc.sql("""
                        select count(*) from audit_event
                        where action = 'REQUEST_FORBIDDEN' and correlation_id in (:a, :b)
                        """)
                        .param("a", first)
                        .param("b", second)
                        .query(Long.class)
                        .single())
                .isEqualTo(2L);
    }

    @Test
    void noTokenIs401() throws Exception {
        MockHttpServletResponse response = mvc.perform(get(ROUTE)).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("UNAUTHENTICATED");
    }

    /**
     * Regression of the review finding "admin route bypass": the filter decided on the raw request URI, so an encoded,
     * parameterised or non-normalised spelling that the dispatcher still routes to the controller could skip it. Runs
     * against the real Tomcat (not MockMvc), so the container's own URI handling is part of the test. A guest token
     * must never get 200 or data; a 403 must leave exactly one REQUEST_FORBIDDEN.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "/api/v1/admin/audit-events",
                "/api/v1/%61dmin/audit-events",
                "/api/v1/admin;x/audit-events",
                "/api/v1/admin;jsessionid=1/audit-events",
                "/api/v1/admin/audit-events;x",
                "//api/v1/admin/audit-events",
                "/api//v1/admin/audit-events",
                "/api/v1//admin/audit-events",
                "/api/v1/./admin/audit-events",
                "/api/v1/x/../admin/audit-events",
                "/api/v1/admin/./audit-events",
                "/api/v1/%2e/admin/audit-events",
                "/api/v1/admin%2Faudit-events",
                "/api/v1/admin/audit%2Devents",
                "/API/v1/admin/audit-events",
                "/api/v1/ADMIN/audit-events",
                "/api/v1/admin/audit-events/"
            })
    void aGuestTokenNeverReadsTheTrailThroughAnyPathSpelling(String path) throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.betaUser(jdbc, wallet.address());
        String accessToken = auth.accessToken(auth.login(wallet));
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();

        java.net.http.HttpResponse<String> response = client.send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + path
                                + "?from=2026-01-01T00:00:00Z&to=2026-01-02T00:00:00Z"))
                        .header("Authorization", "Bearer " + accessToken)
                        .GET()
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).as(path).isNotEqualTo(200).isIn(400, 401, 403, 404, 405);
        assertThat(response.body()).as(path).doesNotContain("\"items\"").doesNotContain("\"eventId\"");
        if (response.statusCode() == 403) {
            String correlation =
                    response.headers().firstValue("X-Correlation-Id").orElseThrow();
            assertThat(jdbc.sql("""
                            select count(*) from audit_outbox
                            where payload->>'correlationId' = :c and payload->>'action' = 'REQUEST_FORBIDDEN'
                            """).param("c", correlation).query(Long.class).single())
                    .as(path)
                    .isEqualTo(1L);
        }
    }

    /**
     * Regression of the review finding "refusal turns into 500 without an event": a client-controlled path that is
     * too long or carries a JWT, a Base58 64-byte signature or a token in the query string made the publisher refuse
     * the REQUEST_FORBIDDEN event, so the guest got 500 and no event was written. The refusal must answer 403 and
     * leave exactly one event whose route carries none of those values.
     */
    @Test
    void aRefusalOnAHostilePathAlwaysAnswers403WithExactlyOneSanitisedEvent() throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.betaUser(jdbc, wallet.address());
        String accessToken = auth.accessToken(auth.login(wallet));
        byte[] signatureBytes = new byte[64];
        new java.security.SecureRandom().nextBytes(signatureBytes);
        signatureBytes[0] = (byte) (signatureBytes[0] | 0x01);
        String signature = com.memetrade.testsupport.SyntheticIds.encode(signatureBytes);
        String jwt = com.memetrade.testsupport.SyntheticJwt.withSubject("guest");
        java.util.Map<String, String> hostile = new java.util.LinkedHashMap<>();
        hostile.put("long path", "/api/v1/admin/audit-events/" + "segment-a/".repeat(40) + "end");
        hostile.put("JWT segment", "/api/v1/admin/" + jwt + "/audit-events");
        hostile.put("signature segment", "/api/v1/admin/" + signature);
        hostile.put("token in the query", "/api/v1/admin/audit-events?token=" + jwt + "&auth=Bearer%20" + jwt);

        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        for (java.util.Map.Entry<String, String> path : hostile.entrySet()) {
            java.net.http.HttpResponse<String> response = client.send(
                    java.net.http.HttpRequest.newBuilder(
                                    java.net.URI.create("http://127.0.0.1:" + port + path.getValue()))
                            .header("Authorization", "Bearer " + accessToken)
                            .GET()
                            .build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).as(path.getKey()).isEqualTo(403);
            assertThat(response.body()).as(path.getKey()).contains("FORBIDDEN_ROLE");
            String correlation =
                    response.headers().firstValue("X-Correlation-Id").orElseThrow();
            java.util.List<String> events =
                    jdbc.sql("""
                            select payload::text from audit_outbox
                            where payload->>'correlationId' = :c and payload->>'action' = 'REQUEST_FORBIDDEN'
                            """).param("c", correlation).query(String.class).list();
            assertThat(events).as(path.getKey()).hasSize(1);
            assertThat(events.getFirst())
                    .as(path.getKey())
                    .doesNotContain(jwt)
                    .doesNotContain(signature)
                    .doesNotContain("Bearer")
                    .doesNotContain("token=");
            String targetRef = json.readTree(events.getFirst()).get("targetRef").asText();
            assertThat(targetRef.length()).as(path.getKey()).isLessThanOrEqualTo(200);
            assertThat(targetRef).as(path.getKey()).startsWith("GET /api/v1/admin/");
        }
    }

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    private String refusedCorrelation(String accessToken) throws Exception {
        MockHttpServletResponse response = mvc.perform(get(ROUTE).header("Authorization", "Bearer " + accessToken))
                .andReturn()
                .getResponse();
        assertThat(response.getStatus()).isEqualTo(403);
        return response.getHeader("X-Correlation-Id");
    }
}
