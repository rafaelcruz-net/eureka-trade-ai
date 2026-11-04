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

    /** U3 (BR2.2): its own counters, never shared with the login limiter. */
    @Bean
    public InviteLookupRateLimiter inviteLookupRateLimiter(IdentityProperties properties, Clock clock) {
        return new InviteLookupRateLimiter(properties.inviteLookupRateLimitPerMinute(), clock);
    }
}
