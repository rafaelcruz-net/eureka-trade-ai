package com.memetrade.identity.infra;

import com.memetrade.identity.domain.ratelimit.InviteLookupRateLimiter;
import com.memetrade.identity.domain.ratelimit.LoginRateLimiter;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RateLimitConfig {

    @Bean
    public LoginRateLimiter loginRateLimiter(IdentityProperties properties, Clock clock) {
        return new LoginRateLimiter(properties.loginRateLimitPerMinute(), clock);
    }

    /** U8 (Q4 of the NFR Design): the administrator entry has its own counters, per IP and per (address, IP). */
    @Bean
    public com.memetrade.identity.domain.ratelimit.AdminLoginLimiter adminLoginLimiter(
            AdminLoginProperties properties, Clock clock) {
        return new com.memetrade.identity.domain.ratelimit.AdminLoginLimiter(
                properties.perIpPerMinute(), properties.perAddressPerMinute(), clock);
    }

    /** U3 (BR2.2): its own counters, never shared with the login limiter. */
    @Bean
    public InviteLookupRateLimiter inviteLookupRateLimiter(IdentityProperties properties, Clock clock) {
        return new InviteLookupRateLimiter(properties.inviteLookupRateLimitPerMinute(), clock);
    }

    /**
     * u1-legal-documents (NFR6.3): its own counters, 60 per client IP and minute by default; the CIDR list exempts only
     * direct peers inside it. An invalid CIDR stops the api at start-up.
     */
    @Bean
    public com.memetrade.identity.domain.ratelimit.LegalDocumentsRateLimiter legalDocumentsRateLimiter(
            LegalProperties properties, Clock clock) {
        return new com.memetrade.identity.domain.ratelimit.LegalDocumentsRateLimiter(
                properties.rateLimitPerMinute(),
                clock,
                com.memetrade.identity.domain.ratelimit.InternalNetworks.parse(
                        properties.rateLimit().internalNetworks()));
    }
}
