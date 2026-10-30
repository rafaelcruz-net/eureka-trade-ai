package com.memetrade.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.CorsFilter;

@Tag("u1")
class GuestCorsPolicyTest {

    private static final String GUEST = "http://localhost:3000";

    private final CorsFilter filter = new CorsFilter(GuestCorsPolicy.source(URI.create(GUEST + "/")));

    @Test
    void aPreflightFromTheGuestOriginIsAllowed() throws Exception {
        MockHttpServletResponse response = preflight(GUEST, "/api/v1/scanner/tokens", "GET");

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(GUEST);
    }

    @Test
    void anOriginNotListedIsRefused() throws Exception {
        MockHttpServletResponse response = preflight("https://evil.example", "/api/v1/auth/verify", "POST");

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void credentialsAreAllowedOnlyOnVerifyRefreshAndLogout() throws Exception {
        for (String route : GuestCorsPolicy.CREDENTIAL_ROUTES) {
            assertThat(preflight(GUEST, route, "POST").getHeader("Access-Control-Allow-Credentials"))
                    .as(route)
                    .isEqualTo("true");
        }
        assertThat(preflight(GUEST, "/api/v1/auth/challenge", "POST").getHeader("Access-Control-Allow-Credentials"))
                .isNull();
        assertThat(preflight(GUEST, "/api/v1/scanner/tokens", "GET").getHeader("Access-Control-Allow-Credentials"))
                .isNull();
    }

    @Test
    void theIdempotencyAndCorrelationHeadersMayBeSent() throws Exception {
        MockHttpServletRequest request = preflightRequest(GUEST, "/api/v1/auth/challenge", "POST");
        request.addHeader("Access-Control-Request-Headers", "idempotency-key, x-correlation-id, content-type");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Access-Control-Allow-Headers")).containsIgnoringCase("idempotency-key");
    }

    private MockHttpServletResponse preflight(String origin, String path, String method) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(preflightRequest(origin, path, method), response, new MockFilterChain());
        return response;
    }

    private static MockHttpServletRequest preflightRequest(String origin, String path, String method) {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", path);
        request.addHeader("Origin", origin);
        request.addHeader("Access-Control-Request-Method", method);
        return request;
    }
}
