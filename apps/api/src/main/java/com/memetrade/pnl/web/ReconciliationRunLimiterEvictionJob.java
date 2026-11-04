package com.memetrade.pnl.web;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Drops the "check now" limiter keys idle for more than 2 minutes, every minute (NFR6.5); no customer data. */
@Component
public class ReconciliationRunLimiterEvictionJob {

    private final ReconciliationRunLimiter limiter;

    public ReconciliationRunLimiterEvictionJob(ReconciliationRunLimiter limiter) {
        this.limiter = limiter;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void evict() {
        limiter.evictIdle();
    }
}
