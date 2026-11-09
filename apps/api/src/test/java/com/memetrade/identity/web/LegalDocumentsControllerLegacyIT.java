package com.memetrade.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.identity.domain.ratelimit.LegalDocumentsRateLimiter;
import com.memetrade.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Passo 12 (BR1.6, BR1.15; NFR7.3): the effective current version is LEGACY and has no privacy policy, and every
 * deployment field is configured, so the version is publishable and the health component is UP.
 */
@Tag("u1-legal-documents")
@TestPropertySource(
        properties = {
            "memetrade.terms.location=classpath:terms-test/legacy-only/",
            "memetrade.legal.storage-location=Brasil (região de teste)",
            "memetrade.legal.international-transfer=Não há transferência internacional (teste)",
            "memetrade.legal.dpo-email=encarregado@example.test"
        })
class LegalDocumentsControllerLegacyIT extends AbstractPostgresIT {

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
    void aLegacyCurrentVersionAnswersWithoutPolicyWithoutSummariesAndWithAnEmptyTable() throws Exception {
        MockHttpServletResponse response =
                mvc.perform(get("/api/v1/legal/current")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(response.getContentAsString());
        assertThat(body.get("version").asText()).isEqualTo("v1");
        assertThat(body.get("provisional").asBoolean()).isTrue();
        assertThat(body.get("privacy").isNull()).isTrue();
        assertThat(body.get("terms").get("summary").isNull()).isTrue();
        assertThat(body.get("terms").get("sections")).hasSize(1);
        assertThat(body.get("terms").get("sections").get(0).get("title").asText())
                .isEqualTo("Termos de uso");
        assertThat(body.get("retention")).isEmpty();
    }

    @Test
    void withEveryDeploymentFieldTheVersionIsPublishableAndTheFieldsAreServed() throws Exception {
        JsonNode body = json.readTree(mvc.perform(get("/api/v1/legal/current"))
                .andReturn()
                .getResponse()
                .getContentAsString());

        assertThat(body.get("publishable").asBoolean()).isTrue();
        assertThat(body.get("deployment").get("storageLocation").asText()).isEqualTo("Brasil (região de teste)");
        assertThat(body.get("deployment").get("dpoEmail").asText()).isEqualTo("encarregado@example.test");
        assertThat(health.healthForPath("legalDocuments").getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void theHistoryHasOnlyTheLegacyVersion() throws Exception {
        JsonNode body = json.readTree(mvc.perform(get("/api/v1/legal/versions"))
                .andReturn()
                .getResponse()
                .getContentAsString());

        assertThat(body).hasSize(1);
        assertThat(body.get(0).get("version").asText()).isEqualTo("v1");
        assertThat(mvc.perform(get("/api/v1/legal/versions/v2"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
    }
}
