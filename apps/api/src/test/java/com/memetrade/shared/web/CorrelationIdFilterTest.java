package com.memetrade.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@Tag("u1")
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void echoesAValidClientCorrelationIdAndExposesItInTheMdcDuringTheRequest() throws Exception {
        String clientId = UUID.randomUUID().toString();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/scanner/tokens");
        request.addHeader("X-Correlation-Id", clientId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInMdc = new AtomicReference<>();

        filter.doFilter(request, response, new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(
                    jakarta.servlet.http.HttpServletRequest req, jakarta.servlet.http.HttpServletResponse res) {
                seenInMdc.set(MDC.get(CorrelationId.MDC_KEY));
            }
        }));

        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(clientId);
        assertThat(seenInMdc.get()).isEqualTo(clientId);
        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
    }

    @Test
    void generatesACorrelationIdWhenMissing() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/x"), response, new MockFilterChain());

        assertThat(UUID.fromString(response.getHeader("X-Correlation-Id"))).isNotNull();
    }

    @Test
    void replacesAnInvalidClientValueInsteadOfTrustingIt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader("X-Correlation-Id", "<script>alert(1)</script>");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("X-Correlation-Id")).doesNotContain("script");
        assertThat(UUID.fromString(response.getHeader("X-Correlation-Id"))).isNotNull();
    }

    @Test
    void scheduledJobsGetTheirOwnCorrelationIdAndRestoreThePreviousOne() {
        MDC.put(CorrelationId.MDC_KEY, "outer");
        String inner = CorrelationId.withNew(CorrelationId::current);

        assertThat(inner).isNotEqualTo("outer");
        assertThat(MDC.get(CorrelationId.MDC_KEY)).isEqualTo("outer");
        MDC.remove(CorrelationId.MDC_KEY);
    }
}
