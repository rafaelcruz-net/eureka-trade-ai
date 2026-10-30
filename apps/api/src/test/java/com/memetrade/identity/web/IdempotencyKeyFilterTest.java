package com.memetrade.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.shared.web.ProblemWriter;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Written before the implementation (Red) from the common rule: every state-changing request needs a UUID key. */
@Tag("u1")
class IdempotencyKeyFilterTest {

    private final IdempotencyKeyFilter filter = new IdempotencyKeyFilter(new ProblemWriter(new ObjectMapper()));

    @Test
    void postWithoutIdempotencyKeyIsRejectedWith400() throws Exception {
        MockHttpServletResponse response = run(new MockHttpServletRequest("POST", "/api/v1/auth/challenge"));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString())
                .contains("\"code\":\"VALIDATION_FAILED\"")
                .contains("Idempotency-Key");
    }

    @Test
    void postWithANonUuidKeyIsRejectedWith400() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/verify");
        request.addHeader(IdempotencyKey.HEADER, "not-a-uuid");

        assertThat(run(request).getStatus()).isEqualTo(400);
    }

    @Test
    void postWithAValidKeyPassesAndExposesTheParsedKey() throws Exception {
        UUID key = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/logout");
        request.addHeader(IdempotencyKey.HEADER, key.toString());

        MockHttpServletResponse response = run(request);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(request.getAttribute(IdempotencyKeyFilter.ATTRIBUTE)).isEqualTo(new IdempotencyKey(key));
    }

    @Test
    void refreshIsExemptAndReadsNeedNoKey() throws Exception {
        assertThat(run(new MockHttpServletRequest("POST", "/api/v1/auth/refresh"))
                        .getStatus())
                .isEqualTo(200);
        assertThat(run(new MockHttpServletRequest("GET", "/api/v1/scanner/tokens"))
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    void putPatchAndDeleteAlsoRequireTheKey() throws Exception {
        for (String method : new String[] {"PUT", "PATCH", "DELETE"}) {
            assertThat(run(new MockHttpServletRequest(method, "/api/v1/anything"))
                            .getStatus())
                    .as(method)
                    .isEqualTo(400);
        }
    }

    private MockHttpServletResponse run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
