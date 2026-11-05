package com.memetrade.scanner.web;

import com.memetrade.identity.api.AuthContext;
import com.memetrade.identity.api.Principal;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.shared.web.FilterOrder;
import com.memetrade.shared.web.PersonRouteRateLimiter;
import com.memetrade.shared.web.ProblemWriter;
import com.memetrade.shared.web.RequestPaths;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Third link of the chain of the U4 routes (security-design.md): after authentication ({@code 401} without a session,
 * never counted) and the role rule, each person may call the list ({@code scanner}) and the detail
 * ({@code assessment}) {@code SCANNER_RATE_LIMIT_PER_MINUTE} times in 60 s; above it {@code 429 RATE_LIMITED} with
 * {@code Retry-After}, without counts in the body. The decision uses the normalised path.
 */
@Component
@Order(FilterOrder.RATE_LIMIT)
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class ScannerRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ScannerRateLimitFilter.class);
    private static final Pattern ASSESSMENT = Pattern.compile("/api/v1/tokens/[^/]+/[^/]+/assessment");

    private final PersonRouteRateLimiter limiter;
    private final AuthContext auth;
    private final ProblemWriter problemWriter;
    private final MeterRegistry meters;

    public ScannerRateLimitFilter(
            PersonRouteRateLimiter limiter, AuthContext auth, ProblemWriter problemWriter, MeterRegistry meters) {
        this.limiter = limiter;
        this.auth = auth;
        this.problemWriter = problemWriter;
        this.meters = meters;
    }

    /** The route name of a U4 request, or empty for any other route. */
    static Optional<String> route(HttpServletRequest request) {
        String path = RequestPaths.normalized(request);
        if ("/api/v1/scanner/tokens".equals(path)) {
            return Optional.of("scanner");
        }
        return ASSESSMENT.matcher(path).matches() ? Optional.of("assessment") : Optional.empty();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equals(request.getMethod()) || route(request).isEmpty();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<Principal> principal = auth.optional();
        if (principal.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        String route = route(request).orElseThrow();
        Optional<Duration> wait = limiter.acquire(principal.get().userId().toString(), route);
        if (wait.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        Counter.builder("scanner_rate_limited_total")
                .tag("route", route)
                .register(meters)
                .increment();
        log.info("Request rate limited: route={}", route);
        problemWriter.write(
                response,
                new ApiException(
                        ErrorCode.RATE_LIMITED,
                        "Too many requests; try again later.",
                        List.of(),
                        null,
                        Map.of("Retry-After", Long.toString(wait.get().toSeconds()))));
    }
}
