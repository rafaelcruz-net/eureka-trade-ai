package com.memetrade.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.domain.ratelimit.InternalNetworks;
import com.memetrade.identity.domain.ratelimit.InviteLookupRateLimiter;
import com.memetrade.identity.domain.ratelimit.LegalDocumentsRateLimiter;
import com.memetrade.identity.domain.ratelimit.LoginRateLimiter;
import com.memetrade.testsupport.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Passo 8 (NFR2.3): one schedule cleans the login limiter AND the invitation lookup limiter. */
@Tag("u3")
class LoginLimiterEvictionJobTest {

    @Test
    void oneRunEvictsTheIdleKeysOfBothLimiters() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter login = new LoginRateLimiter(10, clock);
        InviteLookupRateLimiter lookups = new InviteLookupRateLimiter(10, clock);
        com.memetrade.identity.domain.ratelimit.AdminLoginLimiter admins =
                new com.memetrade.identity.domain.ratelimit.AdminLoginLimiter(5, 5, clock);
        login.acquireForIp("10.0.0.1");
        lookups.acquire("10.0.0.1");
        lookups.acquire("10.0.0.2");
        admins.acquire("10.0.0.3", "11111111111111111111111111111111");
        LegalDocumentsRateLimiter legal = new LegalDocumentsRateLimiter(60, clock, InternalNetworks.none());
        legal.acquire("10.0.0.4", "10.0.0.4");
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));

        new LoginLimiterEvictionJob(login, lookups, admins, legal).evict();

        assertThat(login.trackedKeys()).isZero();
        assertThat(lookups.trackedKeys()).isZero();
        // U8: the administrator entry limiter is cleaned by the same schedule.
        assertThat(admins.trackedKeys()).isZero();
        // u1-legal-documents: so is the limiter of the legal documents.
        assertThat(legal.trackedKeys()).isZero();
    }

    @Test
    void keysStillInsideTheWindowSurviveTheRun() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter login = new LoginRateLimiter(10, clock);
        InviteLookupRateLimiter lookups = new InviteLookupRateLimiter(10, clock);
        lookups.acquire("10.0.0.1");
        clock.advance(Duration.ofSeconds(30));

        new LoginLimiterEvictionJob(
                        login,
                        lookups,
                        new com.memetrade.identity.domain.ratelimit.AdminLoginLimiter(5, 5, clock),
                        new LegalDocumentsRateLimiter(60, clock, InternalNetworks.none()))
                .evict();

        assertThat(lookups.trackedKeys()).isEqualTo(1);
    }
}
