package com.memetrade.identity.infra;

import com.memetrade.identity.domain.ratelimit.InviteLookupRateLimiter;
import com.memetrade.identity.domain.ratelimit.LoginRateLimiter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drops expired limiter windows every minute so the in-memory counters do not grow without bound (NFR3.5). Since U3
 * it cleans both limiters, the login one and the invitation lookup one, in the same schedule (NFR2.3); same entry of
 * the route and job registry.
 */
@Component
@com.memetrade.shared.config.ProcessRole(com.memetrade.shared.config.ProcessRole.Kind.API)
public class LoginLimiterEvictionJob {

    private final LoginRateLimiter limiter;
    private final InviteLookupRateLimiter inviteLookups;
    private final com.memetrade.identity.domain.ratelimit.AdminLoginLimiter adminLogins;

    public LoginLimiterEvictionJob(
            LoginRateLimiter limiter,
            InviteLookupRateLimiter inviteLookups,
            com.memetrade.identity.domain.ratelimit.AdminLoginLimiter adminLogins) {
        this.limiter = limiter;
        this.inviteLookups = inviteLookups;
        this.adminLogins = adminLogins;
    }

    /** Since U8 the administrator entry limiter is cleaned too, in the same schedule (no new thread). */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void evict() {
        limiter.evictExpired();
        inviteLookups.evictExpired();
        adminLogins.evictExpired();
    }
}
