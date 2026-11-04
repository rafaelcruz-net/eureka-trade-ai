package com.memetrade.scanner.infra;

import com.memetrade.shared.web.PersonRouteRateLimiter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Drops the limiter keys idle for more than 2 minutes, every minute, so memory stays bounded (NFR2.8). */
@Component
public class ScannerRateLimitEvictionJob {

    private final PersonRouteRateLimiter limiter;

    public ScannerRateLimitEvictionJob(PersonRouteRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void evict() {
        limiter.evictIdle();
    }
}
