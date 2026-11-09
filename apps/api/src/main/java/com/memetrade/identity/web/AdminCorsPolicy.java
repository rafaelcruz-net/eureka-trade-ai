package com.memetrade.identity.web;

import java.net.URI;
import java.util.List;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CORS of the administrative API (security-design.md "CORS do app do administrador", R-04 and R-08 of the NFR Design):
 * only {@code /api/v1/admin/**}, only the origin of the administrator app ({@code ADMIN_APP_ORIGIN}), methods GET,
 * POST, PUT and OPTIONS, and credentials ONLY on verify (it sets the refresh cookie), refresh and logout of the
 * administrator entry. The guest policy is unchanged and never admits the administrator origin; this policy never
 * admits the guest origin.
 */
public final class AdminCorsPolicy {

    static final List<String> CREDENTIAL_ROUTES =
            List.of("/api/v1/admin/auth/verify", "/api/v1/admin/auth/refresh", "/api/v1/admin/auth/logout");

    static final String ADMIN_ROUTES = "/api/v1/admin/**";

    private AdminCorsPolicy() {}

    /**
     * One source for the whole API: the administrative patterns FIRST (the first matching pattern wins, and
     * {@code /api/v1/**} of the guest policy would otherwise match the administrative routes), then the guest policy.
     */
    public static UrlBasedCorsConfigurationSource combined(URI adminAppOrigin, URI guestAppOrigin) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // u1-legal-documents: the public legal documents first, with BOTH app origins, read only and no credentials.
        GuestCorsPolicy.registerLegal(source, java.util.List.of(guestAppOrigin, adminAppOrigin));
        register(source, adminAppOrigin);
        GuestCorsPolicy.register(source, guestAppOrigin);
        return source;
    }

    static void register(UrlBasedCorsConfigurationSource source, URI adminAppOrigin) {
        String origin = adminAppOrigin.toString().replaceAll("/+$", "");
        for (String route : CREDENTIAL_ROUTES) {
            source.registerCorsConfiguration(route, configuration(origin, true));
        }
        source.registerCorsConfiguration(ADMIN_ROUTES, configuration(origin, false));
    }

    private static CorsConfiguration configuration(String origin, boolean credentials) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(origin));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Correlation-Id"));
        config.setExposedHeaders(List.of("X-Correlation-Id", "Retry-After"));
        config.setAllowCredentials(credentials);
        config.setMaxAge(600L);
        return config;
    }
}
