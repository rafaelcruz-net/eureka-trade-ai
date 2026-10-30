package com.memetrade.shared.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.shared.web.CorrelationIdFilter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag("u1")
class ProblemDetailsHandlerTest {

    private static final String CORRELATION = "6f1c1f1e-1b1a-4c1d-9e1f-1a2b3c4d5e6f";

    private MockMvc mvc;
    private final java.util.List<String> forbidden = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        ObjectMapper strict =
                new ObjectMapper().findAndRegisterModules().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new ProblemDetailsHandler(
                        (request, code, reason) -> forbidden.add(request.getRequestURI() + ":" + code + ":" + reason)))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(strict))
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    @Test
    void apiExceptionRendersProblemJsonWithStableTypeCodeAndCorrelationId() throws Exception {
        mvc.perform(get("/test/conflict").header("X-Correlation-Id", CORRELATION))
                .andExpect(status().isConflict())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://memetrade.app/problems/conflict"))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Idempotency key conflict"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION))
                .andExpect(header().string("X-Correlation-Id", CORRELATION));
    }

    @Test
    void undeclaredFieldIsRejectedWithValidationFailedAndTheFieldName() throws Exception {
        mvc.perform(post("/test/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"a\",\"seedPhrase\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields[0].field").value("seedPhrase"));
    }

    @Test
    void malformedJsonIsValidationFailed() throws Exception {
        mvc.perform(post("/test/echo").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields[0].field").value("body"));
    }

    @Test
    void unexpectedErrorNeverExposesMessageSqlOrStackTrace() throws Exception {
        String body = mvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .doesNotContain("select")
                .doesNotContain("secret_table")
                .doesNotContain("Exception")
                .doesNotContain("at com.memetrade")
                .doesNotContain("trace");
    }

    @Test
    void databaseUnavailableIsServiceUnavailable() throws Exception {
        mvc.perform(get("/test/db-down"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.type").value("https://memetrade.app/problems/unavailable"));
    }

    @Test
    void extraHeadersAndReasonOfTheExceptionAreReturned() throws Exception {
        mvc.perform(get("/test/limited"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "42"))
                .andExpect(jsonPath("$.reason").value("IP_LIMIT"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void unknownRouteIsNotFoundProblem() throws Exception {
        mvc.perform(get("/test/missing-resource"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void every403IsAuditedOnceUnlessTheDomainAlreadyAuditedIt() throws Exception {
        mvc.perform(get("/test/forbidden")).andExpect(status().isForbidden());
        mvc.perform(get("/test/forbidden-audited")).andExpect(status().isForbidden());
        mvc.perform(get("/test/conflict")).andExpect(status().isConflict());

        org.assertj.core.api.Assertions.assertThat(forbidden)
                .containsExactly("/test/forbidden:FORBIDDEN_ROLE:ROLE_NOT_ALLOWED");
    }

    @RestController
    static class ThrowingController {

        @GetMapping("/test/forbidden")
        void forbidden() {
            throw new ApiException(ErrorCode.FORBIDDEN_ROLE, "No.", List.of(), "ROLE_NOT_ALLOWED", Map.of());
        }

        @GetMapping("/test/forbidden-audited")
        void forbiddenAudited() {
            throw new ApiException(ErrorCode.FORBIDDEN_ROLE, "No.", List.of(), "ADMIN_WALLET", Map.of())
                    .alreadyAudited();
        }

        record EchoRequest(String name) {}

        @GetMapping("/test/conflict")
        void conflict() {
            throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT, "Same key, different content.");
        }

        @PostMapping("/test/echo")
        EchoRequest echo(@RequestBody EchoRequest request) {
            return request;
        }

        @GetMapping("/test/boom")
        void boom() {
            throw new IllegalStateException("select password from secret_table");
        }

        @GetMapping("/test/db-down")
        void dbDown() {
            throw new CannotGetJdbcConnectionException("Connection to 10.0.0.1 refused");
        }

        @GetMapping("/test/limited")
        void limited() {
            throw new ApiException(
                    ErrorCode.RATE_LIMITED, "Too many attempts.", List.of(), "IP_LIMIT", Map.of("Retry-After", "42"));
        }

        @GetMapping("/test/missing-resource")
        void missing() throws Exception {
            throw new org.springframework.web.servlet.resource.NoResourceFoundException(
                    org.springframework.http.HttpMethod.GET, "/test/missing-resource");
        }
    }
}
