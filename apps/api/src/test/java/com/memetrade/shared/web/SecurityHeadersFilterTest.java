package com.memetrade.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@Tag("u1")
class SecurityHeadersFilterTest {

    @Test
    void addsTheSecurityHeadersToEveryResponse() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SecurityHeadersFilter()
                .doFilter(new MockHttpServletRequest("GET", "/api/v1/x"), response, new MockFilterChain());

        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }
}
