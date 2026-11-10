package com.memetrade.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.identity.domain.ratelimit.LegalDocumentsRateLimiter;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.actuate.health.SystemHealth;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Passo 12 (US1.1, US1.2, US2.1, US2.6; BR1.7, BR1.9, BR1.10; NFR3.4, NFR6.2, NFR6.5, NFR8.2): the routes of L1 over the
 * real api (PostgreSQL in a container, same migrations), with the TEST catalog "reviewed" (legacy v1 and a v2 reviewed
 * for tests) and no deployment field configured (publishable false, "a definir").
 */
@Tag("u1-legal-documents")
@TestPropertySource(properties = "memetrade.terms.location=classpath:terms-test/reviewed/")
class LegalDocumentsControllerIT extends AbstractPostgresIT {

    private static final String GUEST_ORIGIN = "http://localhost:3000";
    private static final String ADMIN_ORIGIN = "http://localhost:3001";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private LegalDocumentsRateLimiter limiter;

    @Autowired
    private HealthEndpoint health;

    @BeforeEach
    void freshCounters() {
        limiter.clear();
    }

    @Test
    void theCurrentVersionAnswers200WithoutASessionWithTheWholeBodyAndTheCacheHeaders() throws Exception {
        MockHttpServletResponse response =
                mvc.perform(get("/api/v1/legal/current")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("public, max-age=300");
        assertThat(response.getHeader("ETag")).matches("\"[0-9a-f]{32}\"");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        JsonNode body = json.readTree(response.getContentAsString());
        assertThat(body.get("version").asText()).isEqualTo("v2");
        assertThat(body.get("effectiveAt").asText()).isEqualTo("2026-10-15T00:00:00Z");
        assertThat(body.get("provisional").asBoolean()).isFalse();
        assertThat(body.get("publishable").asBoolean()).isFalse();
        assertThat(body.get("terms").get("summary")).hasSize(5);
        assertThat(body.get("terms").get("sections").get(0).get("id").asText()).isEqualTo("uso-do-beta-de-teste");
        assertThat(body.get("terms")
                        .get("sections")
                        .get(0)
                        .get("paragraphs")
                        .get(1)
                        .asText())
                .contains("<script>alert(1)</script>");
        assertThat(body.get("privacy").get("summary")).hasSize(6);
        assertThat(body.get("riskNotice").get("sections")).hasSize(1);
        assertThat(body.get("deployment").has("storageLocation")).isTrue();
        assertThat(body.get("deployment").get("storageLocation").isNull()).isTrue();
        assertThat(body.get("deployment").get("dpoEmail").isNull()).isTrue();
        // u2-retention (BR2.9): the current version shows the retention catalog of the Privacy module (J5), in its
        // order, whatever table the test catalog declares.
        assertThat(body.get("retention")).hasSize(12);
        assertThat(body.get("retention").get(0).get("period").asText()).isEqualTo("Enquanto a conta existir");
        assertThat(body.get("retention").get(10).get("dataItem").asText()).isEqualTo("Sessões e renovações");
        assertThat(body.get("retention").get(10).get("period").asText()).isEqualTo("30 dias após o fim ou a revogação");
    }

    @Test
    void theSameEtagInIfNoneMatchAnswers304WithoutBodyButWithTheCacheHeaders() throws Exception {
        String etag = mvc.perform(get("/api/v1/legal/current"))
                .andReturn()
                .getResponse()
                .getHeader("ETag");

        MockHttpServletResponse response = mvc.perform(
                        get("/api/v1/legal/current").header("If-None-Match", etag))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(304);
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(response.getHeader("ETag")).isEqualTo(etag);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("public, max-age=300");
        assertThat(mvc.perform(get("/api/v1/legal/current").header("If-None-Match", "\"other\""))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    void theHistoryListsTheCurrentAndTheLegacyVersionNewestFirst() throws Exception {
        MockHttpServletResponse response =
                mvc.perform(get("/api/v1/legal/versions")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("public, max-age=300");
        JsonNode body = json.readTree(response.getContentAsString());
        assertThat(body).hasSize(2);
        assertThat(body.get(0).get("version").asText()).isEqualTo("v2");
        assertThat(body.get(1).get("version").asText()).isEqualTo("v1");
        assertThat(body.get(1).get("provisional").asBoolean()).isTrue();
    }

    @Test
    void anOlderVersionOfTheHistoryHasItsTextsAndExplicitNullsForWhatItLacks() throws Exception {
        MockHttpServletResponse response =
                mvc.perform(get("/api/v1/legal/versions/v1")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(response.getContentAsString());
        assertThat(body.get("version").asText()).isEqualTo("v1");
        assertThat(body.has("privacy")).isTrue();
        assertThat(body.get("privacy").isNull()).isTrue();
        assertThat(body.get("terms").get("summary").isNull()).isTrue();
        assertThat(body.get("terms").get("sections")).hasSize(1);
        assertThat(body.get("retention")).isEmpty();
    }

    @Test
    void aVersionOutsideTheHistoryIs404WithNoStore() throws Exception {
        MockHttpServletResponse response =
                mvc.perform(get("/api/v1/legal/versions/v9")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("ETag")).isNull();
        JsonNode problem = json.readTree(response.getContentAsString());
        assertThat(problem.get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(problem.has("correlationId")).isTrue();
    }

    @Test
    void withASessionTheDocumentsAreTheSame() throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.betaUser(jdbc, wallet.address());
        AuthFlow auth = new AuthFlow(mvc, json);
        String token = auth.accessToken(auth.login(wallet));

        String anonymous = mvc.perform(get("/api/v1/legal/current"))
                .andReturn()
                .getResponse()
                .getHeader("ETag");
        MockHttpServletResponse withSession = mvc.perform(
                        get("/api/v1/legal/current").header("Authorization", "Bearer " + token))
                .andReturn()
                .getResponse();

        assertThat(withSession.getStatus()).isEqualTo(200);
        assertThat(withSession.getHeader("ETag")).isEqualTo(anonymous);
        assertThat(mvc.perform(post("/api/v1/legal/current")
                                .header("Authorization", "Bearer " + token)
                                .header(
                                        "Idempotency-Key",
                                        java.util.UUID.randomUUID().toString()))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("another method with a session is not allowed")
                .isEqualTo(405);
    }

    @Test
    void anInvalidBearerDoesNotBlockAPublicRead() throws Exception {
        assertThat(mvc.perform(get("/api/v1/legal/versions").header("Authorization", "Bearer not-a-token"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
    }

    /** HEAD needs no session either; the servlet container (not MockMvc) drops the body of a HEAD answer. */
    @Test
    void headIsAPublicReadWithTheSameHeaders() throws Exception {
        MockHttpServletResponse response =
                mvc.perform(head("/api/v1/legal/current")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("ETag")).matches("\"[0-9a-f]{32}\"");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("public, max-age=300");
    }

    @Test
    void anotherMethodWithoutASessionIs401LikeEveryOtherRoute() throws Exception {
        for (MockHttpServletResponse response : List.of(
                mvc.perform(post("/api/v1/legal/current")).andReturn().getResponse(),
                mvc.perform(post("/api/v1/legal/versions/v2")).andReturn().getResponse())) {
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(json.readTree(response.getContentAsString()).get("code").asText())
                    .isEqualTo("UNAUTHENTICATED");
        }
    }

    @Test
    void corsAcceptsBothAppOriginsWithoutCredentialsAndExposesOnlyTheEtag() throws Exception {
        List<String> allowed = new ArrayList<>();
        for (String origin : List.of(GUEST_ORIGIN, ADMIN_ORIGIN)) {
            MockHttpServletResponse response = mvc.perform(
                            get("/api/v1/legal/current").header("Origin", origin))
                    .andReturn()
                    .getResponse();
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getHeader("Access-Control-Allow-Credentials")).isNull();
            assertThat(response.getHeader("Access-Control-Expose-Headers")).isEqualTo("ETag");
            allowed.add(response.getHeader("Access-Control-Allow-Origin"));
        }
        assertThat(allowed).containsExactly(GUEST_ORIGIN, ADMIN_ORIGIN);
        assertThat(mvc.perform(get("/api/v1/legal/current").header("Origin", "https://evil.example"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
    }

    @Test
    void thePreflightAllowsOnlyReading() throws Exception {
        MockHttpServletResponse read = mvc.perform(options("/api/v1/legal/current")
                        .header("Origin", ADMIN_ORIGIN)
                        .header("Access-Control-Request-Method", "GET"))
                .andReturn()
                .getResponse();
        MockHttpServletResponse write = mvc.perform(options("/api/v1/legal/current")
                        .header("Origin", ADMIN_ORIGIN)
                        .header("Access-Control-Request-Method", "POST"))
                .andReturn()
                .getResponse();

        assertThat(read.getStatus()).isEqualTo(200);
        assertThat(read.getHeader("Access-Control-Allow-Methods")).isEqualTo("GET,HEAD");
        assertThat(write.getStatus()).isEqualTo(403);
    }

    @Test
    void theHealthComponentStaysUpWithoutDeploymentFieldsAndIsOutsideTheReadinessGroup() {
        HealthComponent legal = health.healthForPath("legalDocuments");
        assertThat(legal.getStatus()).isEqualTo(Status.UP);
        assertThat(String.valueOf(legal)).contains("currentVersion=v2").contains("publishable=false");

        HealthComponent readiness = health.healthForPath("readiness");
        assertThat(((CompositeHealth) readiness).getComponents()).doesNotContainKey("legalDocuments");
        HealthComponent all = health.health();
        assertThat(((SystemHealth) all).getComponents()).containsKey("legalDocuments");
    }
}
