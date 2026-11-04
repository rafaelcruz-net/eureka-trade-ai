package com.memetrade.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** The real limit of 60 per person and route through the filter chain (NFR6.3, Step 12). */
@Tag("u4")
@TestPropertySource(properties = "memetrade.scanner.rate-limit-per-minute=60")
class ScannerRateLimitIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    private String token;

    @BeforeEach
    void signIn() throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.betaUser(jdbc, wallet.address());
        AuthFlow auth = new AuthFlow(mvc, json);
        token = auth.accessToken(auth.login(wallet));
    }

    @Test
    void theSixtyFirstListInAMinuteIs429WithRetryAfterAndRateLimited() throws Exception {
        for (int i = 0; i < 60; i++) {
            assertThat(list(token).getStatus()).as("request %d", i + 1).isNotEqualTo(429);
        }

        MockHttpServletResponse refused = list(token);

        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(refused.getHeader("Retry-After")).isEqualTo("60");
        assertThat(json.readTree(refused.getContentAsString()).get("code").asText())
                .isEqualTo("RATE_LIMITED");
        assertThat(refused.getContentAsString()).doesNotContain("60 ").contains("correlationId");
        clock.advance(Duration.ofSeconds(60));
        assertThat(list(token).getStatus()).isNotEqualTo(429);
    }

    @Test
    void theDetailHasItsOwnCounter() throws Exception {
        for (int i = 0; i < 60; i++) {
            list(token);
        }

        assertThat(mvc.perform(get("/api/v1/tokens/solana/FxCommonA1111111111111111111111111111111111/assessment")
                                .header("Authorization", "Bearer " + token))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isNotEqualTo(429);
    }

    @Test
    void withoutASessionTheAnswerIs401AndNothingIsCounted() throws Exception {
        for (int i = 0; i < 70; i++) {
            assertThat(mvc.perform(get("/api/v1/scanner/tokens"))
                            .andReturn()
                            .getResponse()
                            .getStatus())
                    .isEqualTo(401);
        }

        assertThat(list(token).getStatus()).isNotEqualTo(429);
    }

    private MockHttpServletResponse list(String bearer) throws Exception {
        return mvc.perform(get("/api/v1/scanner/tokens").header("Authorization", "Bearer " + bearer))
                .andReturn()
                .getResponse();
    }
}
