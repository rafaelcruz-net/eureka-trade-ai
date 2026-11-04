package com.memetrade.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.audit.api.AuditActions;
import com.memetrade.testsupport.AbstractU3IT;
import com.memetrade.testsupport.AdminSessions;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.OutboxEvents;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestInvites;
import com.memetrade.testsupport.TestWallet;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Passo 13 (C8; BR1.1 to BR1.5, BR1.8; NFR2.2, NFR4.5, NFR6.3, NFR6.10, NFR9.4): the administrative invitation
 * routes over HTTP. The code is in the first 201 only; a replay with the same key returns the same invitation without
 * it and the idempotency record never holds it.
 */
class AdminInviteRouteIT extends AbstractU3IT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private AdminSessions adminSessions;

    private AdminSessions.AdminSession admin;

    @BeforeEach
    void setUp() {
        admin = adminSessions.openAdminSession();
    }

    /** Registered negative test of the three ADMIN routes (NFR6.10, AC7.1.4). */
    @Test
    void aGuestTokenIsRefusedWith403AndOneRequestForbiddenEvent() throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.betaUser(jdbc, wallet.address());
        String guest = "Bearer " + new AuthFlow(mvc, json).accessToken(new AuthFlow(mvc, json).login(wallet));
        UUID tenant = TestInvites.tenant(jdbc, "ACTIVE");
        TestInvites.Created invite = TestInvites.pending(jdbc).insert();

        for (MockHttpServletRequestBuilder request : new MockHttpServletRequestBuilder[] {
            get("/api/v1/admin/invites"),
            issueRequest(tenant, clock.instant().plus(Duration.ofDays(1)), UUID.randomUUID()),
            post("/api/v1/admin/invites/" + invite.inviteId() + "/revoke")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
        }) {
            String correlationId = UUID.randomUUID().toString();
            MockHttpServletResponse response = mvc.perform(
                            request.header("Authorization", guest).header("X-Correlation-Id", correlationId))
                    .andReturn()
                    .getResponse();

            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(json.readTree(response.getContentAsString()).get("code").asText())
                    .isEqualTo("FORBIDDEN_ROLE");
            assertThat(jdbc.sql("""
                                    select count(*) from audit_outbox
                                    where payload->>'correlationId' = :c and payload->>'action' = 'REQUEST_FORBIDDEN'
                                    """).param("c", correlationId).query(Long.class).single())
                    .isEqualTo(1);
        }
        assertThat(invitesOf(tenant)).isZero();
        assertThat(status(invite.inviteId())).isEqualTo("PENDING");
    }

    @Test
    void withoutATokenEveryInvitationRouteIs401() throws Exception {
        assertThat(mvc.perform(get("/api/v1/admin/invites"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
        assertThat(mvc.perform(issueRequest(UUID.randomUUID(), clock.instant(), UUID.randomUUID()))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
    }

    @Test
    void theIssuanceAnswers201WithTheCodeOnceAndTheReplayReturnsTheSameInvitationWithoutIt() throws Exception {
        UUID tenant = TestInvites.tenant(jdbc, "ACTIVE");
        UUID key = UUID.randomUUID();

        MockHttpServletResponse first = issue(tenant, clock.instant().plus(Duration.ofDays(2)), key);
        MockHttpServletResponse replay = issue(tenant, clock.instant().plus(Duration.ofDays(2)), key);

        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(first.getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode issued = json.readTree(first.getContentAsString());
        String code = issued.get("code").asText();
        assertThat(code).hasSize(43).endsWith(issued.get("codeSuffix").asText());
        assertThat(issued.get("status").asText()).isEqualTo("PENDING");
        assertThat(issued.has("issuedBy")).isFalse();
        assertThat(replay.getStatus()).isEqualTo(201);
        JsonNode replayed = json.readTree(replay.getContentAsString());
        assertThat(replayed.get("inviteId")).isEqualTo(issued.get("inviteId"));
        assertThat(replayed.has("code")).as("never in a replay").isFalse();
        assertThat(replay.getContentAsString()).doesNotContain(code);
        assertThat(invitesOf(tenant)).isEqualTo(1);
        assertThat(OutboxEvents.count(
                        jdbc,
                        AuditActions.INVITE_ISSUED,
                        "invite:" + issued.get("inviteId").asText()))
                .isEqualTo(1);
        String stored = jdbc.sql("select response_body::text from idempotency_record where idempotency_key = :k")
                .param("k", key)
                .query(String.class)
                .single();
        assertThat(stored).doesNotContain(code).contains(issued.get("inviteId").asText());
    }

    @Test
    void theSameKeyWithAnotherBodyIs409AndNothingNewIsCreated() throws Exception {
        UUID tenant = TestInvites.tenant(jdbc, "ACTIVE");
        UUID key = UUID.randomUUID();
        issue(tenant, clock.instant().plus(Duration.ofDays(2)), key);

        MockHttpServletResponse conflict = issue(tenant, clock.instant().plus(Duration.ofDays(3)), key);

        assertThat(conflict.getStatus()).isEqualTo(409);
        assertThat(json.readTree(conflict.getContentAsString()).get("code").asText())
                .isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(invitesOf(tenant)).isEqualTo(1);
    }

    @Test
    void withoutAnIdempotencyKeyTheIssuanceAndTheRevocationAre400AndNothingIsWritten() throws Exception {
        UUID tenant = TestInvites.tenant(jdbc, "ACTIVE");
        TestInvites.Created invite = TestInvites.pending(jdbc).insert();

        MockHttpServletResponse issuance = mvc.perform(post("/api/v1/admin/invites")
                        .header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(tenant, clock.instant().plus(Duration.ofDays(1)))))
                .andReturn()
                .getResponse();
        MockHttpServletResponse revocation = mvc.perform(post("/api/v1/admin/invites/" + invite.inviteId() + "/revoke")
                        .header("Authorization", admin.bearer()))
                .andReturn()
                .getResponse();

        assertThat(issuance.getStatus()).isEqualTo(400);
        assertThat(issuance.getContentAsString()).contains("Idempotency-Key");
        assertThat(revocation.getStatus()).isEqualTo(400);
        assertThat(invitesOf(tenant)).isZero();
        assertThat(status(invite.inviteId())).isEqualTo("PENDING");
    }

    @Test
    void aSuspendedTenantOrAValidityOutOfRangeIs422ValidationFailedOnTheField() throws Exception {
        UUID suspended = TestInvites.tenant(jdbc, "SUSPENDED");
        UUID active = TestInvites.tenant(jdbc, "ACTIVE");

        JsonNode tenant = problem(issue(suspended, clock.instant().plus(Duration.ofDays(1)), UUID.randomUUID()), 422);
        JsonNode tooLong = problem(issue(active, clock.instant().plus(Duration.ofDays(31)), UUID.randomUUID()), 422);
        JsonNode tooShort =
                problem(issue(active, clock.instant().plus(Duration.ofMinutes(59)), UUID.randomUUID()), 422);

        assertThat(tenant.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(tenant.get("fields").get(0).get("field").asText()).isEqualTo("tenantId");
        assertThat(tooLong.get("fields").get(0).get("field").asText()).isEqualTo("expiresAt");
        assertThat(tooShort.get("fields").get(0).get("field").asText()).isEqualTo("expiresAt");
        assertThat(invitesOf(active)).isZero();
        MockHttpServletResponse missing = mvc.perform(post("/api/v1/admin/invites")
                        .header("Authorization", admin.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"" + active + "\"}"))
                .andReturn()
                .getResponse();
        assertThat(missing.getStatus()).isEqualTo(400);
    }

    @Test
    void theListShowsStateValidityAndSuffixButNeverTheCodeNorWhoIssuedIt() throws Exception {
        clock.set(java.time.Instant.parse("2260-01-01T00:00:00Z"));
        UUID tenant = TestInvites.tenant(jdbc, "ACTIVE");
        String code = json.readTree(issue(tenant, clock.instant().plus(Duration.ofDays(1)), UUID.randomUUID())
                        .getContentAsString())
                .get("code")
                .asText();

        MockHttpServletResponse response = mvc.perform(
                        get("/api/v1/admin/invites?limit=100").header("Authorization", admin.bearer()))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        String body = response.getContentAsString();
        assertThat(body).doesNotContain(code).doesNotContain("issuedBy").doesNotContain("\"code\"");
        JsonNode page = json.readTree(body);
        assertThat(page.has("nextCursor")).isTrue();
        JsonNode first = page.get("items").get(0);
        assertThat(first.get("codeSuffix").asText()).isEqualTo(code.substring(39));
        assertThat(first.get("status").asText()).isEqualTo("PENDING");
        assertThat(first.has("revokedAt")).isTrue();
    }

    @Test
    void aLimitOutside1To100Is400AndTheDefaultPageIs20() throws Exception {
        for (String limit : new String[] {"0", "101", "abc"}) {
            assertThat(mvc.perform(get("/api/v1/admin/invites?limit=" + limit).header("Authorization", admin.bearer()))
                            .andReturn()
                            .getResponse()
                            .getStatus())
                    .as(limit)
                    .isEqualTo(400);
        }
        for (int i = 0; i < 21; i++) {
            TestInvites.pending(jdbc).insert();
        }
        JsonNode page = json.readTree(mvc.perform(get("/api/v1/admin/invites").header("Authorization", admin.bearer()))
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(page.get("items")).hasSize(20);
        assertThat(page.get("nextCursor").isNull()).isFalse();
    }

    @Test
    void revocationAnswers200ThenTheSameInvitationAnd404Or409WhenItCannotBeRevoked() throws Exception {
        TestInvites.Created pending = TestInvites.pending(jdbc).insert();
        TestInvites.Created expired = TestInvites.pending(jdbc)
                .expiresAt(clock.instant().minusSeconds(1))
                .insert();
        UUID key = UUID.randomUUID();

        MockHttpServletResponse revoked = revoke(pending.inviteId(), key);
        MockHttpServletResponse replay = revoke(pending.inviteId(), key);
        MockHttpServletResponse again = revoke(pending.inviteId(), UUID.randomUUID());

        assertThat(revoked.getStatus()).isEqualTo(200);
        assertThat(json.readTree(revoked.getContentAsString()).get("status").asText())
                .isEqualTo("REVOKED");
        assertThat(replay.getContentAsString()).isEqualTo(revoked.getContentAsString());
        assertThat(again.getStatus()).isEqualTo(200);
        assertThat(OutboxEvents.count(jdbc, AuditActions.INVITE_REVOKED, "invite:" + pending.inviteId()))
                .isEqualTo(1);
        JsonNode notRevocable = problem(revoke(expired.inviteId(), UUID.randomUUID()), 409);
        assertThat(notRevocable.get("code").asText()).isEqualTo("INVITE_NOT_REVOCABLE");
        assertThat(notRevocable.get("reason").asText()).isEqualTo("EXPIRED");
        assertThat(problem(revoke(UUID.randomUUID(), UUID.randomUUID()), 404)
                        .get("code")
                        .asText())
                .isEqualTo("NOT_FOUND");
        assertThat(mvc.perform(post("/api/v1/admin/invites/not-a-uuid/revoke")
                                .header("Authorization", admin.bearer())
                                .header("Idempotency-Key", UUID.randomUUID().toString()))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
    }

    @Test
    void anIssuedCodeCreatesAnAccountThroughTheGuestEntry() throws Exception {
        UUID tenant = TestInvites.tenant(jdbc, "ACTIVE");
        String code = json.readTree(issue(tenant, clock.instant().plus(Duration.ofDays(1)), UUID.randomUUID())
                        .getContentAsString())
                .get("code")
                .asText();

        assertThat(new AuthFlow(mvc, json)
                        .createAccount(TestWallet.generate(), code)
                        .getStatus())
                .isEqualTo(200);
    }

    // ----- helpers -----

    private MockHttpServletResponse issue(UUID tenant, java.time.Instant expiresAt, UUID key) throws Exception {
        return mvc.perform(issueRequest(tenant, expiresAt, key).header("Authorization", admin.bearer()))
                .andReturn()
                .getResponse();
    }

    private MockHttpServletRequestBuilder issueRequest(UUID tenant, java.time.Instant expiresAt, UUID key)
            throws Exception {
        return post("/api/v1/admin/invites")
                .header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(tenant, expiresAt));
    }

    private MockHttpServletResponse revoke(UUID inviteId, UUID key) throws Exception {
        return mvc.perform(post("/api/v1/admin/invites/" + inviteId + "/revoke")
                        .header("Authorization", admin.bearer())
                        .header("Idempotency-Key", key.toString()))
                .andReturn()
                .getResponse();
    }

    private String body(UUID tenant, java.time.Instant expiresAt) throws Exception {
        return json.writeValueAsString(
                java.util.Map.of("tenantId", tenant.toString(), "expiresAt", expiresAt.toString()));
    }

    private JsonNode problem(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        return json.readTree(response.getContentAsString());
    }

    private long invitesOf(UUID tenant) {
        return jdbc.sql("select count(*) from invite where tenant_id = :t")
                .param("t", tenant)
                .query(Long.class)
                .single();
    }

    private String status(UUID inviteId) {
        return jdbc.sql("select status from invite where invite_id = :id")
                .param("id", inviteId)
                .query(String.class)
                .single();
    }
}
