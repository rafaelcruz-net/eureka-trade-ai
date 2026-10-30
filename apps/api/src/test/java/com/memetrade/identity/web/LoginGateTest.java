package com.memetrade.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.memetrade.identity.domain.ratelimit.LoginRateLimiter;
import com.memetrade.shared.error.ApiException;
import com.memetrade.shared.error.ErrorCode;
import com.memetrade.testsupport.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

@Tag("u1")
class LoginGateTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final LoginGate gate = new LoginGate(
            new LoginRateLimiter(10, new MutableClock()), new ClientIpResolver(List.of()), new LoginMetrics(registry));

    @Test
    void challengeAndVerificationShareTheIpBudgetAndThe11thGets429WithRetryAfter() {
        MockHttpServletRequest request = from("10.1.1.1");
        for (int i = 0; i < 5; i++) {
            gate.beforeChallenge(request);
            gate.beforeVerification(request, "wallet");
        }

        assertThatThrownBy(() -> gate.beforeChallenge(request)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.RATE_LIMITED);
            assertThat(e.code().status()).isEqualTo(429);
            assertThat(e.headers()).containsKey("Retry-After");
            assertThat(e.reason()).isEqualTo("IP_LIMIT");
        });
        assertThat(registry.get("rate_limit_rejections_total")
                        .tag("target", "ip")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void refusedVerificationsBlockTheWalletOnlyForTheIpsThatMadeThem() {
        for (int i = 0; i < 11; i++) {
            gate.afterRejectedVerification(from("6.6.6." + i), "victim");
        }

        assertThatThrownBy(() -> gate.beforeVerification(from("6.6.6.3"), "victim"))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.reason()).isEqualTo("WALLET_LIMIT"));
        gate.beforeVerification(from("192.168.1.20"), "victim");
    }

    private static MockHttpServletRequest from(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/verify");
        request.setRemoteAddr(ip);
        return request;
    }
}
