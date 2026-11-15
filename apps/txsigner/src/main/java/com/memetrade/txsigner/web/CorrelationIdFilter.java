package com.memetrade.txsigner.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads {@code X-Correlation-Id} (repassed by the worker, NFR5.13) when it is a canonical UUID, otherwise generates
 * one; keeps it in the MDC of the request and returns it on the response.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = acceptOrGenerate(request.getHeader(HEADER));
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    public static String current() {
        String id = MDC.get(MDC_KEY);
        return id == null ? UUID.randomUUID().toString() : id;
    }

    static String acceptOrGenerate(String candidate) {
        if (candidate != null) {
            try {
                UUID parsed = UUID.fromString(candidate);
                if (parsed.toString().equalsIgnoreCase(candidate)) {
                    return candidate.toLowerCase();
                }
            } catch (IllegalArgumentException notUuid) {
                // Arbitrary client text is never trusted as a correlation id: a fresh one is generated below.
            }
        }
        return UUID.randomUUID().toString();
    }
}
