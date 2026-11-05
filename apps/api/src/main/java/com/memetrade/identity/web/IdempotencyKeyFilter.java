package com.memetrade.identity.web;

import com.memetrade.identity.api.IdempotencyKey;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.web.FilterOrder;
import com.memetrade.shared.web.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Every POST, PUT, PATCH or DELETE under {@code /api/v1} requires a UUID {@code Idempotency-Key}, except the token
 * refresh, which changes no business state (contract-summary.md, Q7/Q20). The parsed key is exposed as a request
 * attribute for the controllers.
 */
@Component
@Order(FilterOrder.IDEMPOTENCY_KEY)
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class IdempotencyKeyFilter extends OncePerRequestFilter {

    public static final String ATTRIBUTE = IdempotencyKeyFilter.class.getName() + ".key";

    private static final Set<String> STATE_CHANGING = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> EXEMPT = Set.of("/api/v1/auth/refresh", "/api/v1/admin/auth/refresh");

    private final ProblemWriter problemWriter;

    public IdempotencyKeyFilter(ProblemWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/v1/") || !STATE_CHANGING.contains(request.getMethod()) || EXEMPT.contains(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            request.setAttribute(ATTRIBUTE, IdempotencyKey.parse(request.getHeader(IdempotencyKey.HEADER)));
        } catch (ApiException invalid) {
            problemWriter.write(response, invalid);
            return;
        }
        chain.doFilter(request, response);
    }
}
