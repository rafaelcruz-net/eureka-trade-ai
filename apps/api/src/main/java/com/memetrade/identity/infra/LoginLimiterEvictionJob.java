package com.memetrade.identity.infra;

import com.memetrade.identity.domain.ratelimit.LoginRateLimiter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Drops expired limiter windows every minute so the in-memory counters do not grow without bound (NFR3.5). */
@Component
public class LoginLimiterEvictionJob {

    private final LoginRateLimiter limiter;

    public LoginLimiterEvictionJob(LoginRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void evict() {
        limiter.evictExpired();
    }
}
