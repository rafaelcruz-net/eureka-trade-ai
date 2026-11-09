package com.memetrade.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.identity.domain.ratelimit.LegalDocumentsRateLimiter;
import com.memetrade.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Passo 12 (NFR6.3, security-design.md "Testes de segurança"): 60 requests per client IP and minute on
 * {@code /api/v1/legal/**}; the internal range of the apps is exempt by the DIRECT PEER only, so a forged
 * {@code X-Forwarded-For} from an external peer is limited and the bridge gateway is not exempt.
 */
@Tag("u1-legal-documents")
@TestPropertySource(
        properties = {
            "memetrade.terms.location=classpath:terms-test/reviewed/",
            "memetrade.legal.rate-limit.internal-networks=172.28.1.0/24"
        })
class LegalDocumentsRateLimiterIT extends AbstractPostgresIT {

    private static final int LIMIT = 60;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private LegalDocumentsRateLimiter limiter;

    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void freshCountersAndLogs() {
        limiter.clear();
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(LegalDocumentsRateLimitFilter.class)).addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        ((Logger) LoggerFactory.getLogger(LegalDocumentsRateLimitFilter.class)).detachAppender(logs);
    }

    @Test
    void anExternalIpGets429WithRetryAfterAndNoStoreAfterItsLimit() throws Exception {
        assertThat(limiter.limit()).isEqualTo(LIMIT);
        for (int i = 0; i < LIMIT; i++) {
            assertThat(call("203.0.113.7", null).getStatus()).isEqualTo(200);
        }

        MockHttpServletResponse refused = call("203.0.113.7", null);

        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(Integer.parseInt(refused.getHeader("Retry-After"))).isBetween(1, 60);
        assertThat(refused.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(refused.getHeader("ETag")).isNull();
        assertThat(refused.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        JsonNode problem = json.readTree(refused.getContentAsString());
        assertThat(problem.get("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(problem.has("correlationId")).isTrue();
        assertThat(logs.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("Legal documents rate limit exceeded; route=/api/v1/legal/**")
                .noneMatch(line -> line.contains("203.0.113.7"));
    }

    @Test
    void aForgedInternalForwardedForFromAnExternalPeerIsStillLimited() throws Exception {
        for (int i = 0; i < LIMIT; i++) {
            call("203.0.113.8", "172.28.1.10");
        }

        assertThat(call("203.0.113.8", "172.28.1.10").getStatus()).isEqualTo(429);
    }

    @Test
    void aDirectPeerOfTheInternalRangeIsNeverLimited() throws Exception {
        for (int i = 0; i < LIMIT * 2; i++) {
            assertThat(call("172.28.1.10", null).getStatus()).isEqualTo(200);
        }
    }

    @Test
    void theBridgeGatewayOfThePublishedPortsIsNotExempt() throws Exception {
        for (int i = 0; i < LIMIT; i++) {
            call("172.28.0.1", null);
        }

        assertThat(call("172.28.0.1", null).getStatus()).isEqualTo(429);
    }

    @Test
    void theLimitOfTheLegalDocumentsNeverTouchesAnotherRoute() throws Exception {
        for (int i = 0; i < LIMIT; i++) {
            call("203.0.113.9", null);
        }

        MockHttpServletResponse other = mvc.perform(
                        get("/api/v1/strategy-templates").with(request -> {
                            request.setRemoteAddr("203.0.113.9");
                            return request;
                        }))
                .andReturn()
                .getResponse();

        assertThat(other.getStatus()).isNotEqualTo(429);
    }

    private MockHttpServletResponse call(String directPeer, String forwardedFor) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/legal/current").with(servlet -> {
            servlet.setRemoteAddr(directPeer);
            return servlet;
        });
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return mvc.perform(request).andReturn().getResponse();
    }
}
