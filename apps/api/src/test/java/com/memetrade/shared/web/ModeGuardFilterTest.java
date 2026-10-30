package com.memetrade.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@Tag("u1")
class ModeGuardFilterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> recorded = new ArrayList<>();
    private final ModeGuardFilter filter = new ModeGuardFilter(
            mapper, new ProblemWriter(mapper), (request, code, reason) -> recorded.add(code + ":" + reason));

    @Test
    void realModeInTheQueryIsRefusedWith403AndAudited() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/scanner/tokens");
        request.setQueryString("mode=REAL");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"code\":\"REAL_MODE_UNAVAILABLE\"");
        assertThat(recorded).containsExactly(ErrorCode.REAL_MODE_UNAVAILABLE + ":MODE_NOT_PAPER");
    }

    @Test
    void anyNonPaperModeInTheBodyIsRefused() throws Exception {
        for (String body : List.of("{\"mode\":\"REAL\"}", "{\"mode\":\"paper\"}", "{\"mode\":null}", "{\"mode\":1}")) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(jsonPost(body), response, new MockFilterChain());
            assertThat(response.getStatus()).as(body).isEqualTo(403);
        }
        assertThat(recorded).hasSize(4);
    }

    @Test
    void paperModeAndRequestsWithoutModePassThrough() throws Exception {
        MockHttpServletRequest query = new MockHttpServletRequest("GET", "/api/v1/scanner/tokens");
        query.setQueryString("mode=PAPER&limit=10");
        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilter(query, first, new MockFilterChain());

        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(jsonPost("{\"walletAddress\":\"abc\"}"), second, new MockFilterChain());

        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(second.getStatus()).isEqualTo(200);
        assertThat(recorded).isEmpty();
    }

    @Test
    void theInspectedBodyIsStillReadableDownstream() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse res) throws IOException {
                seen.set(new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            }
        };

        filter.doFilter(
                jsonPost("{\"walletAddress\":\"abc\"}"), new MockHttpServletResponse(), new MockFilterChain(servlet));

        assertThat(seen.get()).isEqualTo("{\"walletAddress\":\"abc\"}");
    }

    @Test
    void malformedJsonIsLeftToTheControllerAndOversizedBodiesAreRefused() throws Exception {
        MockHttpServletResponse malformed = new MockHttpServletResponse();
        filter.doFilter(jsonPost("{mode"), malformed, new MockFilterChain());
        assertThat(malformed.getStatus()).isEqualTo(200);

        MockHttpServletResponse oversized = new MockHttpServletResponse();
        filter.doFilter(jsonPost("x".repeat(CachedBodyRequest.MAX_BODY_BYTES + 1)), oversized, new MockFilterChain());
        assertThat(oversized.getStatus()).isEqualTo(400);
    }

    @Test
    void routesOutsideTheApiAreNotInspected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.setQueryString("mode=REAL");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private static MockHttpServletRequest jsonPost(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/challenge");
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
