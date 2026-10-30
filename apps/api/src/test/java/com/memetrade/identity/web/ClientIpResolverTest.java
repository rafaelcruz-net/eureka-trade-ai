package com.memetrade.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

@Tag("u1")
class ClientIpResolverTest {

    @Test
    void xForwardedForIsIgnoredWithoutTrustedProxies() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("X-Forwarded-For", "1.2.3.4");

        assertThat(new ClientIpResolver(List.of()).resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void aTrustedProxyForwardsTheLastUntrustedHop() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.2");
        request.addHeader("X-Forwarded-For", "9.9.9.9, 198.51.100.4, 10.0.0.1");

        assertThat(new ClientIpResolver(List.of("10.0.0.1", "10.0.0.2")).resolve(request))
                .isEqualTo("198.51.100.4");
    }

    @Test
    void aTrustedProxyWithoutTheHeaderFallsBackToTheDirectPeer() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.2");

        assertThat(new ClientIpResolver(List.of("10.0.0.2")).resolve(request)).isEqualTo("10.0.0.2");
    }
}
