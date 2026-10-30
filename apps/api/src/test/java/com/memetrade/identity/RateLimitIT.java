package com.memetrade.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.TestWallet;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/** Runs in the DEFAULT profile (limit 10), as the security design requires for the 429 tests. */
class RateLimitIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Test
    void theEleventhChallengeFromOneIpIs429WithRetryAfterAndAProblem() throws Exception {
        TestWallet wallet = TestWallet.generate();
        for (int i = 0; i < 10; i++) {
            assertThat(challenge(wallet, "198.51.100.10", null).getStatus()).isEqualTo(200);
        }

        MockHttpServletResponse limited = challenge(wallet, "198.51.100.10", null);

        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(limited.getHeader("Retry-After"))).isBetween(1L, 60L);
        assertThat(json.readTree(limited.getContentAsString()).get("code").asText())
                .isEqualTo("RATE_LIMITED");
        assertThat(challenge(wallet, "198.51.100.11", null).getStatus())
                .as("another IP")
                .isEqualTo(200);
    }

    @Test
    void xForwardedForCannotBeUsedToEscapeTheLimit() throws Exception {
        TestWallet wallet = TestWallet.generate();
        for (int i = 0; i < 10; i++) {
            challenge(wallet, "198.51.100.20", "203.0.113." + i);
        }

        assertThat(challenge(wallet, "198.51.100.20", "203.0.113.99").getStatus())
                .isEqualTo(429);
    }

    @Test
    void theLimitWindowExpires() throws Exception {
        TestWallet wallet = TestWallet.generate();
        for (int i = 0; i < 11; i++) {
            challenge(wallet, "198.51.100.30", null);
        }
        clock.advance(Duration.ofMinutes(1));

        assertThat(challenge(wallet, "198.51.100.30", null).getStatus()).isEqualTo(200);
    }

    private MockHttpServletResponse challenge(TestWallet wallet, String ip, String forwardedFor) throws Exception {
        var request = post("/api/v1/auth/challenge")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"walletAddress\":\"" + wallet.address() + "\"}")
                .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                });
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return mvc.perform(request).andReturn().getResponse();
    }
}
