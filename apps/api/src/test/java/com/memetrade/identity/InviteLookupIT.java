package com.memetrade.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.testsupport.AbstractU3IT;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestInvites;
import com.memetrade.testsupport.TestWallet;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Passo 9 (F4; BR2.1 to BR2.3; NFR6.5 to NFR6.7): the narrow public rule of the authentication filter, every derived
 * state, malformed equal to unknown, the per-IP limit and the response headers.
 */
class InviteLookupIT extends AbstractU3IT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Test
    void aGetWithoutTokenReachesTheLookupAndAnswersThePendingStateWithTheCurrentTerms() throws Exception {
        TestInvites.Created invite = TestInvites.pending(jdbc).insert();

        MockHttpServletResponse response = lookup(invite.code());

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(response.getContentAsString());
        assertThat(body.get("status").asText()).isEqualTo("PENDING");
        assertThat(body.get("expiresAt").asText()).isEqualTo(invite.expiresAt().toString());
        assertThat(body.get("termsVersion").asText()).isEqualTo("v1");
        assertThat(body.get("termsText").asText()).startsWith("Versão provisória");
        assertThat(body.get("riskNoticeText").asText()).contains("modo REAL");
    }

    @Test
    void theAnswerNeverCarriesTheTenantTheInviteIdOrTheCode() throws Exception {
        TestInvites.Created invite = TestInvites.pending(jdbc).insert();

        String body = lookup(invite.code()).getContentAsString();

        assertThat(json.readTree(body).fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("status", "expiresAt", "termsVersion", "termsText", "riskNoticeText");
        assertThat(body)
                .doesNotContain(invite.tenantId().toString())
                .doesNotContain(invite.inviteId().toString())
                .doesNotContain(invite.code());
    }

    @Test
    void onlyAGetOnExactlyOneSegmentIsPublicEverythingElseUnderThePrefixNeedsASession() throws Exception {
        String code = TestInvites.newCode();

        assertThat(mvc.perform(post("/api/v1/invites/abc")
                                .header("Idempotency-Key", UUID.randomUUID().toString()))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
        assertThat(status(get("/api/v1/invites/abc/def"))).isEqualTo(401);
        assertThat(status(get("/api/v1/invites"))).isEqualTo(401);
        assertThat(status(get("/api/v1/invites/"))).isEqualTo(401);
        assertThat(status(get("/api/v1/invites/" + code + "/x"))).isEqualTo(401);
        assertThat(status(get("/api/v1/invites/" + "a".repeat(129)))).isEqualTo(401);
        assertThat(status(get("/api/v1/invites/" + code))).isEqualTo(404);
    }

    @Test
    void eachDerivedStateIsAnswered() throws Exception {
        TestInvites.Created expiring = TestInvites.pending(jdbc)
                .expiresAt(clock.instant().plus(Duration.ofHours(2)))
                .insert();
        TestInvites.Created revoked =
                TestInvites.pending(jdbc).revokedAt(clock.instant()).insert();
        TestAccounts.Seeded account =
                TestAccounts.betaUser(jdbc, TestWallet.generate().address());
        TestInvites.Created used =
                TestInvites.pending(jdbc).tenant(account.tenantId()).insert();
        jdbc.sql("update invite set status = 'USED', used_at = now(), used_by_user_id = :u where invite_id = :id")
                .param("u", account.userId())
                .param("id", used.inviteId())
                .update();

        assertThat(state(revoked.code())).isEqualTo("REVOKED");
        assertThat(state(used.code())).isEqualTo("USED");
        clock.set(expiring.expiresAt());
        assertThat(state(expiring.code())).as("valid up to expiresAt inclusive").isEqualTo("PENDING");
        clock.set(expiring.expiresAt().plusSeconds(1));
        assertThat(state(expiring.code())).isEqualTo("EXPIRED");
    }

    @Test
    void aMalformedCodeGetsExactlyTheAnswerOfAnUnknownOne() throws Exception {
        JsonNode unknown = problem(lookup(TestInvites.newCode()), 404);
        JsonNode tooShort = problem(lookup("A".repeat(42)), 404);
        JsonNode badAlphabet = problem(lookup("A".repeat(42) + "*"), 404);

        for (JsonNode malformed : new JsonNode[] {tooShort, badAlphabet}) {
            assertThat(malformed.get("code").asText()).isEqualTo("INVITE_NOT_FOUND");
            assertThat(malformed.get("detail")).isEqualTo(unknown.get("detail"));
            assertThat(malformed.get("type")).isEqualTo(unknown.get("type"));
            assertThat(malformed.get("title")).isEqualTo(unknown.get("title"));
            assertThat(malformed.has("reason")).isEqualTo(unknown.has("reason"));
        }
        assertThat(unknown.get("correlationId").asText()).isNotBlank();
    }

    @Test
    void theEleventhLookupInAMinuteGets429WithRetryAfterAndMalformedLookupsCount() throws Exception {
        TestInvites.Created invite = TestInvites.pending(jdbc).insert();
        for (int i = 0; i < 9; i++) {
            assertThat(lookup("bad").getStatus()).isEqualTo(404);
        }
        assertThat(lookup(invite.code()).getStatus()).as("the tenth passes").isEqualTo(200);

        MockHttpServletResponse eleventh = lookup(invite.code());

        JsonNode problem = problem(eleventh, 429);
        assertThat(problem.get("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(eleventh.getContentAsString()).doesNotContain("PENDING");
        assertThat(eleventh.getHeader("Retry-After")).isEqualTo("60");
        clock.advance(Duration.ofSeconds(60));
        assertThat(lookup(invite.code()).getStatus())
                .as("after Retry-After it passes again")
                .isEqualTo(200);
    }

    @Test
    void theLookupCounterIsSeparateFromTheLoginCounter() throws Exception {
        for (int i = 0; i < 11; i++) {
            lookup(TestInvites.newCode());
        }
        TestWallet wallet = TestWallet.generate();
        TestAccounts.betaUser(jdbc, wallet.address());

        assertThat(new AuthFlow(mvc, json).login(wallet).getStatus()).isEqualTo(200);
    }

    @Test
    void everyAnswerIsNeverCachedAndSendsNoReferrer() throws Exception {
        TestInvites.Created invite = TestInvites.pending(jdbc).insert();

        for (MockHttpServletResponse response :
                new MockHttpServletResponse[] {lookup(invite.code()), lookup(TestInvites.newCode())}) {
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        }
    }

    private MockHttpServletResponse lookup(String code) throws Exception {
        return mvc.perform(get("/api/v1/invites/" + code)).andReturn().getResponse();
    }

    private int status(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private String state(String code) throws Exception {
        MockHttpServletResponse response = lookup(code);
        assertThat(response.getStatus()).isEqualTo(200);
        return json.readTree(response.getContentAsString()).get("status").asText();
    }

    private JsonNode problem(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        return json.readTree(response.getContentAsString());
    }
}
