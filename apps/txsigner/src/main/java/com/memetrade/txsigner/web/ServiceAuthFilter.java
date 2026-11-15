package com.memetrade.txsigner.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.txsigner.infra.credential.ServiceCredentials;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.Set;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Service authentication of the internal routes (contract R1, NFR1.5): without a valid service token the answer is
 * 401; with a client certificate (mTLS in the cloud) whose CN is not {@code api} nor {@code worker}, 403. Locally there
 * is no mTLS and the caller is {@code unknown}. Every failure counts in {@code txsigner_auth_failures_total} by status
 * and caller; the token itself is never logged.
 */
public class ServiceAuthFilter extends OncePerRequestFilter {

    public static final String CALLER_ATTRIBUTE = "txsigner.caller";
    private static final Set<String> ALLOWED_CALLERS = Set.of("api", "worker");
    private static final String BEARER = "Bearer ";
    private static final String CERTIFICATE_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";

    private final ServiceCredentials credentials;
    private final MeterRegistry meters;
    private final ObjectMapper json;

    public ServiceAuthFilter(ServiceCredentials credentials, MeterRegistry meters, ObjectMapper json) {
        this.credentials = credentials;
        this.meters = meters;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String caller = callerOf(request);
        String header = request.getHeader("Authorization");
        String token = header != null && header.startsWith(BEARER) ? header.substring(BEARER.length()) : null;
        if (!credentials.accepts(token)) {
            reject(response, 401, "UNAUTHENTICATED", caller);
            return;
        }
        if (!"unknown".equals(caller) && !ALLOWED_CALLERS.contains(caller)) {
            reject(response, 403, "FORBIDDEN", caller);
            return;
        }
        request.setAttribute(CALLER_ATTRIBUTE, caller);
        chain.doFilter(request, response);
    }

    /** CN of the client certificate, or {@code unknown} without mTLS (local Compose). */
    static String callerOf(HttpServletRequest request) {
        if (request.getAttribute(CERTIFICATE_ATTRIBUTE) instanceof X509Certificate[] chain && chain.length > 0) {
            try {
                for (Rdn rdn : new LdapName(chain[0].getSubjectX500Principal().getName()).getRdns()) {
                    if ("CN".equalsIgnoreCase(rdn.getType())) {
                        return String.valueOf(rdn.getValue());
                    }
                }
            } catch (InvalidNameException malformed) {
                return "invalid";
            }
            return "invalid";
        }
        return "unknown";
    }

    private void reject(HttpServletResponse response, int status, String code, String caller) throws IOException {
        Counter.builder("txsigner_auth_failures")
                .description("Refused calls to the TxSigner by status and caller")
                .tag("status", String.valueOf(status))
                .tag("caller", ALLOWED_CALLERS.contains(caller) || "unknown".equals(caller) ? caller : "other")
                .register(meters)
                .increment();
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), Problem.of(status, code, CorrelationIdFilter.current(), null));
    }
}
