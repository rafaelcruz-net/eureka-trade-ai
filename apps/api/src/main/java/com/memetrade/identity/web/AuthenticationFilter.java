package com.memetrade.identity.web;

import com.memetrade.identity.api.UnauthenticatedException;
import com.memetrade.identity.domain.session.AuthenticatedSession;
import com.memetrade.identity.domain.session.SessionService;
import com.memetrade.shared.web.FilterOrder;
import com.memetrade.shared.web.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates every {@code /api/v1} route except challenge, verify and refresh (contract-summary.md
 * "Autenticação"): reads the Bearer access token, checks session, user and tenant by primary key (BR1.14) and binds
 * the principal for {@link RequestAuthContext}. Anything else, including a login signature presented as a credential,
 * is {@code 401 UNAUTHENTICATED}.
 */
@Component
@Order(FilterOrder.AUTHENTICATION)
public class AuthenticationFilter extends OncePerRequestFilter {

    static final Set<String> PUBLIC_ROUTES =
            Set.of("/api/v1/auth/challenge", "/api/v1/auth/verify", "/api/v1/auth/refresh");
    private static final String BEARER = "Bearer ";

    private final SessionService sessions;
    private final ProblemWriter problemWriter;

    public AuthenticationFilter(SessionService sessions, ProblemWriter problemWriter) {
        this.sessions = sessions;
        this.problemWriter = problemWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // The normalised path, as the dispatcher routes it: "//api/v1/..." or "/api/v1/%73canner/..." still need a
        // session.
        String path = com.memetrade.shared.web.RequestPaths.normalized(request);
        return !path.startsWith("/api/v1/") || PUBLIC_ROUTES.contains(path) || "OPTIONS".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<AuthenticatedSession> session = bearer(request).flatMap(sessions::authenticate);
        if (session.isEmpty()) {
            problemWriter.write(response, new UnauthenticatedException());
            return;
        }
        RequestAuthContext.bind(session.get());
        try {
            chain.doFilter(request, response);
        } finally {
            RequestAuthContext.clear();
        }
    }

    private static Optional<String> bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            return Optional.empty();
        }
        return Optional.of(header.substring(BEARER.length()).trim()).filter(token -> !token.isEmpty());
    }
}
