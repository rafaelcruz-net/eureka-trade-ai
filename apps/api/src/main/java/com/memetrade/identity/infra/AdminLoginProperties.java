package com.memetrade.identity.infra;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fixed settings of the administrator entry of U8 ({@code memetrade.admin.login}, Q1 of the functional design and Q4 of
 * the NFR Design). Never read from the environment.
 *
 * @param perIpPerMinute challenge and verify requests of one IP in one minute (5)
 * @param perAddressPerMinute challenge and verify requests of one (address, IP) pair in one minute (5)
 * @param accessTokenTtl lifetime of the administrator access token (15 minutes)
 * @param sessionMaxAge lifetime of the administrator session, never extended (8 hours)
 */
@ConfigurationProperties(prefix = "memetrade.admin.login")
public record AdminLoginProperties(
        int perIpPerMinute, int perAddressPerMinute, Duration accessTokenTtl, Duration sessionMaxAge) {

    public AdminLoginProperties {
        perIpPerMinute = perIpPerMinute == 0 ? 5 : perIpPerMinute;
        perAddressPerMinute = perAddressPerMinute == 0 ? 5 : perAddressPerMinute;
        accessTokenTtl = accessTokenTtl == null ? Duration.ofMinutes(15) : accessTokenTtl;
        sessionMaxAge = sessionMaxAge == null ? Duration.ofHours(8) : sessionMaxAge;
        if (perIpPerMinute < 1 || perAddressPerMinute < 1) {
            throw new IllegalArgumentException("memetrade.admin.login limits must be positive");
        }
        if (accessTokenTtl.compareTo(sessionMaxAge) > 0) {
            throw new IllegalArgumentException(
                    "memetrade.admin.login.access-token-ttl must not exceed session-max-age");
        }
    }
}
