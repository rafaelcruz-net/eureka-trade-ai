package com.memetrade.identity.web;

import java.net.URI;
import java.util.List;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CORS of the guest API (security-design.md, NFR6.9): a fixed list with only the guest app origin; credentials
 * are allowed only on verify (it returns the refresh cookie, ADR-0001), refresh and logout.
 */
public final class GuestCorsPolicy {

    static final List<String> CREDENTIAL_ROUTES =
            List.of("/api/v1/auth/verify", "/api/v1/auth/refresh", "/api/v1/auth/logout");

    private GuestCorsPolicy() {}

    /** u1-legal-documents: the public legal documents, registered BEFORE {@code /api/v1/**} (first match wins). */
    static final String LEGAL_ROUTES = "/api/v1/legal/**";

    public static UrlBasedCorsConfigurationSource source(URI guestAppOrigin) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        registerLegal(source, List.of(guestAppOrigin));
        register(source, guestAppOrigin);
        return source;
    }

    /**
     * u1-legal-documents (BR1.7, NFR6.2): the legal documents accept the origins of the apps that read them (guest and
     * administrator), only {@code GET} and {@code HEAD}, WITHOUT credentials, exposing only {@code ETag}. Must be
     * registered before any wider pattern that also matches {@code /api/v1/legal/**}.
     */
    static void registerLegal(UrlBasedCorsConfigurationSource source, List<URI> appOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(appOrigins.stream()
                .map(origin -> origin.toString().replaceAll("/+$", ""))
                .toList());
        config.setAllowedMethods(List.of("GET", "HEAD"));
        config.setAllowedHeaders(List.of("If-None-Match", "X-Correlation-Id"));
        config.setExposedHeaders(List.of("ETag"));
        config.setAllowCredentials(false);
        config.setMaxAge(600L);
        source.registerCorsConfiguration(LEGAL_ROUTES, config);
    }

    /** U8: the guest patterns appended to a shared source, after the administrative ones (AdminCorsPolicy). */
    static void register(UrlBasedCorsConfigurationSource source, URI guestAppOrigin) {
        String origin = guestAppOrigin.toString().replaceAll("/+$", "");
        // Registration order matters: the first matching pattern wins.
        for (String route : CREDENTIAL_ROUTES) {
            source.registerCorsConfiguration(route, configuration(origin, true));
        }
        // 261113-trading-real-bot U7 (BR6, S48): the person cancels a conditional exit with a DELETE; only these routes
        // take it, every other route with token still refuses DELETE.
        CorsConfiguration conditionals = configuration(origin, false);
        conditionals.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
        source.registerCorsConfiguration(CONDITIONAL_ROUTES, conditionals);
        source.registerCorsConfiguration("/api/v1/**", configuration(origin, false));
    }

    static final String CONDITIONAL_ROUTES = "/api/v1/real/conditional-orders/**";

    private static CorsConfiguration configuration(String origin, boolean credentials) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(origin));
        // The cookie routes take only POST; the bearer routes also take the PUT of the bot draft (U5, F2) and, since
        // 261113-trading-real-bot U6 (U6-NFR1.7), the PATCH of the REAL preferences.
        config.setAllowedMethods(
                credentials ? List.of("GET", "POST", "OPTIONS") : List.of("GET", "POST", "PUT", "PATCH", "OPTIONS"));
        // U9 (BR5.3): the live channel C7 reopens with Last-Event-ID, a header the bearer routes must accept.
        config.setAllowedHeaders(
                credentials
                        ? List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Correlation-Id")
                        : List.of(
                                "Authorization",
                                "Content-Type",
                                "Idempotency-Key",
                                "X-Correlation-Id",
                                "Last-Event-ID"));
        config.setExposedHeaders(List.of("X-Correlation-Id", "Retry-After"));
        config.setAllowCredentials(credentials);
        config.setMaxAge(600L);
        return config;
    }
}
