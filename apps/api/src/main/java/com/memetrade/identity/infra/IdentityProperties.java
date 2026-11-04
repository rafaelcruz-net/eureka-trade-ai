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
        Integer inviteLookupRateLimitPerMinute,
        List<String> trustedProxies) {

    /** U3 (NFR6.6): 10 in the default profile; the loadtest profile raises it, never above this bound. */
    public static final int MAX_INVITE_LOOKUP_RATE_LIMIT = 100_000;

    public static final int DEFAULT_INVITE_LOOKUP_RATE_LIMIT = 10;

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
        inviteLookupRateLimitPerMinute = inviteLookupRateLimitPerMinute == null
                ? DEFAULT_INVITE_LOOKUP_RATE_LIMIT
                : inviteLookupRateLimitPerMinute;
        if (inviteLookupRateLimitPerMinute < 1 || inviteLookupRateLimitPerMinute > MAX_INVITE_LOOKUP_RATE_LIMIT) {
            // Checked at start-up: the api refuses to start, naming the setting and the variable that raises it.
            throw new IllegalArgumentException("memetrade.auth.invite-lookup-rate-limit-per-minute"
                    + " (INVITE_LOOKUP_RATE_LIMIT_PER_MINUTE in the loadtest profile) must be between 1 and "
                    + MAX_INVITE_LOOKUP_RATE_LIMIT);
        }
    }
}
