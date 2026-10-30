package com.memetrade.identity.web;

import com.memetrade.identity.api.AuthContext;
import com.memetrade.shared.web.FilterOrder;
import com.memetrade.shared.web.ProblemWriter;
import com.memetrade.shared.web.RequestPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Administrative route rule (C8, BR2.1): only an administrator passes {@code /api/v1/admin} and everything below it.
 * Runs right after authentication, so a request without a valid token already got {@code 401}; any other role gets
 * {@code 403 FORBIDDEN_ROLE}, recorded once per refusal as {@code REQUEST_FORBIDDEN}.
 *
 * <p>The decision uses the NORMALISED path ({@link RequestPaths#isAdministrative}: decoded, without {@code ;}
 * content, slashes collapsed, dot segments resolved, case-insensitive), never the raw request URI: spellings such as
 * {@code /api/v1/%61dmin/...} or {@code /api/v1/admin;x/...} reach the administrative controller and must meet this
 * rule (regression of the review finding "admin route bypass"). The role check itself lives in
 * {@link AdminAuthorization}, which each administrative operation also calls.
 */
@Component
@Order(FilterOrder.ADMIN_ROUTE)
public class AdminRouteFilter extends OncePerRequestFilter {

    private final AuthContext auth;
    private final ProblemWriter problemWriter;
    private final AdminAuthorization authorization;

    public AdminRouteFilter(AuthContext auth, ProblemWriter problemWriter, AdminAuthorization authorization) {
        this.auth = auth;
        this.problemWriter = problemWriter;
        this.authorization = authorization;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equals(request.getMethod()) || !RequestPaths.isAdministrative(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (authorization.isAdministrator(auth.optional())) {
            chain.doFilter(request, response);
            return;
        }
        problemWriter.write(response, authorization.refuse(request));
    }
}
