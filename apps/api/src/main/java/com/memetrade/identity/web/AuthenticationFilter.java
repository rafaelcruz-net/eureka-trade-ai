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
import java.util.regex.Pattern;
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
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class AuthenticationFilter extends OncePerRequestFilter {

    static final Set<String> PUBLIC_ROUTES = Set.of(
            "/api/v1/auth/challenge",
            "/api/v1/auth/verify",
            "/api/v1/auth/refresh",
            // U8 (security-design.md R-02): the administrator entry, by the whole normalised path; logout needs a
            // token.
            "/api/v1/admin/auth/challenge",
            "/api/v1/admin/auth/verify",
            "/api/v1/admin/auth/refresh");

    /** U8: the public routes of the administrator entry, also the exception of {@link AdminRouteFilter}. */
    static final Set<String> ADMIN_PUBLIC_ROUTES =
            Set.of("/api/v1/admin/auth/challenge", "/api/v1/admin/auth/verify", "/api/v1/admin/auth/refresh");

    /**
     * U3 (security-design.md, NFR6.6): the invitation lookup is public ONLY for GET with the whole normalised path
     * matching exactly one segment after {@code /api/v1/invites/}. The segment is wider than a valid code on purpose:
     * a malformed code reaches the controller and gets the same 404 as an unknown one (NFR6.7). Anything else under
     * the prefix ({@code /api/v1/invites}, {@code /api/v1/invites/}, deeper paths, other methods) still needs a session.
     */
    static final Pattern PUBLIC_INVITE_LOOKUP = Pattern.compile("^/api/v1/invites/[^/]{1,128}$");

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
        return !path.startsWith("/api/v1/")
                || PUBLIC_ROUTES.contains(path)
                || "OPTIONS".equals(request.getMethod())
                || isInvitationLookup(request.getMethod(), path)
                || isLegalDocumentRead(request.getMethod(), path);
    }

    /**
     * u1-legal-documents (BR1.7, NFR6.2): the legal documents are public ONLY for reading ({@code GET} and
     * {@code HEAD}) under {@code /api/v1/legal/}; any other method there still needs a session and gets {@code 401}
     * without one, like every other route.
     */
    static boolean isLegalDocumentRead(String method, String normalizedPath) {
        return ("GET".equals(method) || "HEAD".equals(method)) && normalizedPath.startsWith(LEGAL_PREFIX);
    }

    static final String LEGAL_PREFIX = "/api/v1/legal/";

    static boolean isInvitationLookup(String method, String normalizedPath) {
        return "GET".equals(method)
                && PUBLIC_INVITE_LOOKUP.matcher(normalizedPath).matches();
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
