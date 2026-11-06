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

    public static UrlBasedCorsConfigurationSource source(URI guestAppOrigin) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        register(source, guestAppOrigin);
        return source;
    }

    /** U8: the guest patterns appended to a shared source, after the administrative ones (AdminCorsPolicy). */
    static void register(UrlBasedCorsConfigurationSource source, URI guestAppOrigin) {
        String origin = guestAppOrigin.toString().replaceAll("/+$", "");
        // Registration order matters: the first matching pattern wins.
        for (String route : CREDENTIAL_ROUTES) {
            source.registerCorsConfiguration(route, configuration(origin, true));
        }
        source.registerCorsConfiguration("/api/v1/**", configuration(origin, false));
    }

    private static CorsConfiguration configuration(String origin, boolean credentials) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(origin));
        // The cookie routes take only POST; the bearer routes also take the PUT of the bot draft (U5, F2).
        config.setAllowedMethods(
                credentials ? List.of("GET", "POST", "OPTIONS") : List.of("GET", "POST", "PUT", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Correlation-Id"));
        config.setExposedHeaders(List.of("X-Correlation-Id", "Retry-After"));
        config.setAllowCredentials(credentials);
        config.setMaxAge(600L);
        return config;
    }
}
