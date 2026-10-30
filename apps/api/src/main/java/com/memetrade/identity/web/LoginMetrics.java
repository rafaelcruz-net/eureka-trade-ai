package com.memetrade.identity.web;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** IdentityAccess metrics (observability-design.md); labels never carry an address, token or person id. */
@Component
public class LoginMetrics {

    private final MeterRegistry registry;

    public LoginMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void accepted() {
        Counter.builder("login_attempts_total")
                .tag("result", "accepted")
                .tag("reason", "NONE")
                .register(registry)
                .increment();
    }

    public void rejected(String reason) {
        Counter.builder("login_attempts_total")
                .tag("result", "rejected")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    public void rateLimited(String target) {
        Counter.builder("rate_limit_rejections_total")
                .tag("target", target)
                .register(registry)
                .increment();
    }

    public void refreshReused() {
        Counter.builder("session_refresh_reuse_total").register(registry).increment();
    }
}
