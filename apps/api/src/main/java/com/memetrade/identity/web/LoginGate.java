package com.memetrade.identity.web;

import com.memetrade.identity.domain.ratelimit.LoginRateLimiter;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Applies the login limits to challenge and verification and answers 429 with Retry-After (BR1.11). */
@Component
public class LoginGate {

    private static final Logger log = LoggerFactory.getLogger(LoginGate.class);

    private final LoginRateLimiter limiter;
    private final ClientIpResolver ips;
    private final LoginMetrics metrics;

    public LoginGate(LoginRateLimiter limiter, ClientIpResolver ips, LoginMetrics metrics) {
        this.limiter = limiter;
        this.ips = ips;
        this.metrics = metrics;
    }

    /** Challenge requests count only per IP. */
    public void beforeChallenge(HttpServletRequest request) {
        enforce(limiter.acquireForIp(ips.resolve(request)), "ip");
    }

    /** Verification counts per IP, then honours a wallet block that applies to this IP. */
    public void beforeVerification(HttpServletRequest request, String wallet) {
        String ip = ips.resolve(request);
        enforce(limiter.acquireForIp(ip), "ip");
        enforce(limiter.walletBlockedFor(wallet, ip), "wallet");
    }

    public void afterRejectedVerification(HttpServletRequest request, String wallet) {
        limiter.recordRejectedVerification(wallet, ips.resolve(request));
    }

    private void enforce(Optional<Duration> wait, String target) {
        if (wait.isEmpty()) {
            return;
        }
        long seconds = Math.max(1, (long) Math.ceil(wait.get().toMillis() / 1000.0));
        metrics.rateLimited(target);
        log.warn("Login rate limit exceeded; target={}", target);
        throw new ApiException(
                ErrorCode.RATE_LIMITED,
                "Too many login attempts; try again later.",
                List.of(),
                target.toUpperCase() + "_LIMIT",
                Map.of("Retry-After", Long.toString(seconds)));
    }
}
