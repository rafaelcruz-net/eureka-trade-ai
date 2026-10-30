package com.memetrade.identity.infra;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Authentication settings (memetrade.auth). The signing key comes only from the environment, never the repo. */
@ConfigurationProperties(prefix = "memetrade.auth")
public record IdentityProperties(
        String accessTokenSigningKey,
        URI guestAppOrigin,
        List<String> adminWalletAddresses,
        String chain,
        Duration challengeTtl,
        Duration accessTokenTtl,
        Duration refreshTtl,
        Duration sessionMaxAge,
        int loginRateLimitPerMinute,
        List<String> trustedProxies) {

    public IdentityProperties {
        adminWalletAddresses = adminWalletAddresses == null
                ? List.of()
                : adminWalletAddresses.stream()
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
        trustedProxies = trustedProxies == null
                ? List.of()
                : trustedProxies.stream()
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
    }
}
