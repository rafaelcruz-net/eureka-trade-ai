package com.memetrade.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.shared.web.ProblemWriter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Passo 1 of u3-reacceptance (NFR1.1): {@code currentVersion} is an optional extension of the problem, written only
 * when present, the same way by the controller advice and by the {@code ProblemWriter} of the filters; the two new
 * codes have their status and stable type.
 */
@Tag("u3-reacceptance")
class ProblemResponseTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void theTwoTermsCodesHaveTheirStatusAndTheTermsType() {
        assertThat(ErrorCode.TERMS_REACCEPTANCE_REQUIRED.status()).isEqualTo(403);
        assertThat(ErrorCode.TERMS_VERSION_OUTDATED.status()).isEqualTo(409);
        assertThat(ErrorCode.TERMS_REACCEPTANCE_REQUIRED.type().toString())
                .isEqualTo("https://memetrade.app/problems/terms");
        assertThat(ErrorCode.TERMS_VERSION_OUTDATED.code()).isEqualTo("TERMS_VERSION_OUTDATED");
    }

    @Test
    void anExceptionWithTheCurrentVersionCarriesItInTheProblem() {
        ApiException refused = ApiException.withCurrentVersion(ErrorCode.TERMS_VERSION_OUTDATED, "Outdated.", "v2");

        ProblemResponse problem = ProblemResponse.of(refused, "corr");

        assertThat(problem.currentVersion()).isEqualTo("v2");
        assertThat(problem.code()).isEqualTo("TERMS_VERSION_OUTDATED");
        assertThat(problem.status()).isEqualTo(409);
        assertThat(problem.correlationId()).isEqualTo("corr");
    }

    @Test
    void anyOtherProblemHasNoCurrentVersionInItsBody() throws Exception {
        ProblemResponse problem = ProblemResponse.of(ApiException.validation("version", "Required."), "corr");

        JsonNode body = json.readTree(json.writeValueAsString(problem));

        assertThat(problem.currentVersion()).isNull();
        assertThat(body.has("currentVersion")).isFalse();
        assertThat(ProblemResponse.of(ErrorCode.NOT_FOUND, "x", "corr").currentVersion())
                .isNull();
    }

    @Test
    void theExtensionsAreCopiedAndImmutable() {
        ApiException refused = new ApiException(
                ErrorCode.TERMS_REACCEPTANCE_REQUIRED,
                "Locked.",
                List.of(),
                null,
                Map.of(),
                Map.of(ProblemResponse.CURRENT_VERSION, "v3"));

        assertThat(refused.extensions()).containsExactly(Map.entry("currentVersion", "v3"));
        assertThat(new ApiException(ErrorCode.NOT_FOUND, "x").extensions()).isEmpty();
    }

    @Test
    void theProblemWriterOfTheFiltersWritesTheSameExtension() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ProblemWriter(json)
                .write(
                        response,
                        ApiException.withCurrentVersion(ErrorCode.TERMS_REACCEPTANCE_REQUIRED, "Locked.", "v2"));

        JsonNode body = json.readTree(response.getContentAsString());
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(body.get("currentVersion").asText()).isEqualTo("v2");
        assertThat(body.get("code").asText()).isEqualTo("TERMS_REACCEPTANCE_REQUIRED");
    }
}
