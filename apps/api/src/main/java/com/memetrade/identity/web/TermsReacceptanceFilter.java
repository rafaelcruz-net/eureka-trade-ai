package com.memetrade.identity.web;

import com.memetrade.identity.api.AuthContext;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.identity.domain.terms.CurrentTerms;
import com.memetrade.identity.domain.terms.TermsAcceptanceLookup;
import com.memetrade.identity.domain.terms.TermsEvents;
import com.memetrade.identity.infra.ReacceptanceProperties;
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
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * u3-reacceptance (F1; BR3.1 to BR3.3; L3; NFR2.2, NFR2.5, NFR3.2, NFR3.4, NFR8.1, NFR8.3): the reacceptance lock.
 *
 * <p>Runs after authentication and the administrative route rule, only in the api process (the worker has no web layer,
 * so the active bots keep their cycle). It acts only on a {@code BETA_USER} principal: an administrator token and a
 * request without a principal (public routes) pass to their own rules. A path under one of the allowed prefixes passes;
 * any other guest route asks the database whether the person has an acceptance of the EFFECTIVE current version, in
 * its tenant (not only the latest acceptance: when the current version goes back to a version already accepted, the
 * person stays up to date). Without it, inclusive for an account without any acceptance, the answer is {@code 403
 * TERMS_REACCEPTANCE_REQUIRED} with {@code currentVersion}. The lock is not an attempt of undue access, so it publishes
 * no {@code REQUEST_FORBIDDEN} and writes no log line; it only counts the refusal.
 *
 * <p>The decision uses the normalised path ({@link RequestPaths#normalized}), never the raw URI, and a prefix matches
 * the whole path or a path below it, never a path that only starts with the same text. When the database cannot answer
 * the lock writes {@code 503 SERVICE_UNAVAILABLE} itself (filters run before the controller advice) and never lets the
 * request go on unchecked; the log carries only the class of the exception.
 */
@Component
@Order(FilterOrder.TERMS_REACCEPTANCE)
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class TermsReacceptanceFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TermsReacceptanceFilter.class);
    private static final String API_PREFIX = "/api/v1/";

    private final AuthContext auth;
    private final CurrentTerms terms;
    private final TermsAcceptanceLookup lookup;
    private final List<String> allowedPrefixes;
    private final ProblemWriter problemWriter;
    private final TermsEvents events;

    public TermsReacceptanceFilter(
            AuthContext auth,
            CurrentTerms terms,
            TermsAcceptanceLookup lookup,
            ReacceptanceProperties properties,
            ProblemWriter problemWriter,
            TermsEvents events) {
        this.auth = auth;
        this.terms = terms;
        this.lookup = lookup;
        this.allowedPrefixes = List.copyOf(properties.allowedPrefixes());
        this.problemWriter = problemWriter;
        this.events = events;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equals(request.getMethod())
                || !RequestPaths.normalized(request).startsWith(API_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<Principal> principal = auth.optional();
        if (principal.isEmpty()
                || principal.get().role() != Role.BETA_USER
                || isAllowed(RequestPaths.normalized(request))
                || com.memetrade.identity.domain.realterms.RealTermsRoutes.isReachableWithoutNewAcceptance(
                        request.getMethod(), RequestPaths.normalized(request))) {
            chain.doFilter(request, response);
            return;
        }
        Principal person = principal.get();
        String current;
        boolean accepted;
        try {
            current = terms.current().version();
            accepted = lookup.hasAccepted(person.userId(), person.tenantId(), current);
        } catch (DataAccessException | IllegalStateException unavailable) {
            // Fail closed with an RFC 7807 body: the request never passes without the check (NFR2.5).
            log.error(
                    "Terms reacceptance check failed: {}",
                    unavailable.getClass().getSimpleName());
            problemWriter.write(
                    response, new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Service temporarily unavailable."));
            return;
        }
        if (accepted) {
            chain.doFilter(request, response);
            return;
        }
        events.blocked();
        problemWriter.write(
                response,
                ApiException.withCurrentVersion(
                        ErrorCode.TERMS_REACCEPTANCE_REQUIRED,
                        "The current version of the terms must be accepted before using this route.",
                        current));
    }

    /** The whole path equals a prefix, or lies below it; a prefix ending in "/" already marks its sub-tree. */
    boolean isAllowed(String path) {
        for (String prefix : allowedPrefixes) {
            if (path.equals(prefix)) {
                return true;
            }
            String tree = prefix.endsWith("/") ? prefix : prefix + "/";
            if (path.startsWith(tree)) {
                return true;
            }
        }
        return false;
    }
}
