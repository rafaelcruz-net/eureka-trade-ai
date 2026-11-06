package com.memetrade.identity.domain.login;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Settings of the administrator entry (Q1 of the functional design, security-design.md):
 *
 * @param appOrigin origin of the administrator app; its host and port are the domain of the administrator challenge, so
 *     a guest challenge (domain of the guest app) never serves here and an administrator challenge never serves there
 * @param accessTokenTtl 15 minutes
 * @param sessionMaxAge 8 hours, never extended
 * @param systemTenant the tenant where the administrator accounts live (the tenant "Beta" seeded by U3)
 */
public record AdminLoginSettings(URI appOrigin, Duration accessTokenTtl, Duration sessionMaxAge, UUID systemTenant) {

    /** The tenant "Beta" of migration 0007 (BR1.7 of U3). */
    public static final UUID SYSTEM_TENANT = UUID.fromString("00000000-0000-4000-8000-0000000000b3");

    public AdminLoginSettings {
        Objects.requireNonNull(appOrigin, "appOrigin");
        Objects.requireNonNull(accessTokenTtl, "accessTokenTtl");
        Objects.requireNonNull(sessionMaxAge, "sessionMaxAge");
        Objects.requireNonNull(systemTenant, "systemTenant");
    }

    /** Domain shown in the administrator message: host and port of the administrator app origin. */
    public String domain() {
        return appOrigin.getPort() < 0 ? appOrigin.getHost() : appOrigin.getHost() + ":" + appOrigin.getPort();
    }

    public String uri() {
        return appOrigin.toString();
    }
}
