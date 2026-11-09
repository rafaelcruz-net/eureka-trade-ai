package com.memetrade.identity.web;

import com.memetrade.identity.domain.ratelimit.LegalDocumentsRateLimiter;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.web.FilterOrder;
import com.memetrade.shared.web.ProblemWriter;
import com.memetrade.shared.web.RequestPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-IP limit of {@code /api/v1/legal/**} (NFR6.3), right after the security headers and CORS, so a {@code 429}
 * carries the headers and {@code Cache-Control: no-store}. Every request under the prefix counts, before any session
 * or route is looked at. The answer is {@code 429 RATE_LIMITED} with {@code Retry-After}; the log line names the route
 * template only, never the IP.
 */
@Component
@Order(FilterOrder.LEGAL_RATE_LIMIT)
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class LegalDocumentsRateLimitFilter extends OncePerRequestFilter {

    static final String PREFIX = "/api/v1/legal/";
    static final String ROUTE = "/api/v1/legal/**";

    private static final Logger log = LoggerFactory.getLogger(LegalDocumentsRateLimitFilter.class);

    private final LegalDocumentsRateLimiter limiter;
    private final ClientIpResolver ips;
    private final ProblemWriter problemWriter;

    public LegalDocumentsRateLimitFilter(
            LegalDocumentsRateLimiter limiter, ClientIpResolver ips, ProblemWriter problemWriter) {
        this.limiter = limiter;
        this.ips = ips;
        this.problemWriter = problemWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isLegalRoute(RequestPaths.normalized(request));
    }

    static boolean isLegalRoute(String normalizedPath) {
        return normalizedPath.startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<Duration> wait = limiter.acquire(ips.resolve(request), request.getRemoteAddr());
        if (wait.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        long seconds = Math.max(1, (long) Math.ceil(wait.get().toMillis() / 1000.0));
        log.warn("Legal documents rate limit exceeded; route={}", ROUTE);
        problemWriter.write(
                response,
                new ApiException(
                        ErrorCode.RATE_LIMITED,
                        "Too many requests for the legal documents; try again later.",
                        List.of(),
                        "IP_LIMIT",
                        Map.of("Retry-After", Long.toString(seconds))));
    }
}
