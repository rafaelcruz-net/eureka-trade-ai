package com.memetrade.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.audit.infra.AuditOutboxRelay;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

class AuthSessionIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private AuditOutboxRelay relay;

    private AuthFlow auth;

    @BeforeEach
    void setUp() {
        auth = new AuthFlow(mvc, json);
    }

    @Test
    void refreshRotatesTheCookieAndTheDatabaseKeepsOnlyDigests() throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.Seeded seeded = TestAccounts.betaUser(jdbc, wallet.address());
        String refreshToken = AuthFlow.refreshToken(auth.login(wallet));

        MockHttpServletResponse refreshed = refresh(refreshToken);

        assertThat(refreshed.getStatus()).isEqualTo(200);
        String rotated = AuthFlow.refreshToken(refreshed);
        assertThat(rotated).isNotEqualTo(refreshToken);
        List<String> digests =
                jdbc.sql("""
                        select refresh_token_digest from user_session where user_id = :u
                        union all
                        select d.digest from session_used_refresh_digest d
                          join user_session s on s.session_id = d.session_id where s.user_id = :u
                        """).param("u", seeded.userId()).query(String.class).list();
        assertThat(digests).hasSize(2).doesNotContain(refreshToken, rotated);
    }

    @Test
    void aRefreshWithoutCookieNeverClearsACookieSetByAConcurrentLogin() throws Exception {
        // Regression: the anonymous refresh fired when /entrar loads answered with a clearing Set-Cookie that could
        // arrive after the login and delete the fresh refresh cookie.
        MockHttpServletResponse anonymous =
                mvc.perform(post("/api/v1/auth/refresh")).andReturn().getResponse();

        assertThat(anonymous.getStatus()).isEqualTo(401);
        assertThat(anonymous.getHeader("Set-Cookie")).isNull();
        assertThat(refresh("not-a-known-token").getHeader("Set-Cookie")).contains("Max-Age=0");
    }

    @Test
    void reusingAnExchangedRefreshTokenRevokesTheSession() throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.betaUser(jdbc, wallet.address());
        MockHttpServletResponse login = auth.login(wallet);
        String first = AuthFlow.refreshToken(login);
        String second = AuthFlow.refreshToken(refresh(first));

        MockHttpServletResponse reuse = refresh(first);

        assertThat(reuse.getStatus()).isEqualTo(401);
        assertThat(reuse.getHeader("Set-Cookie")).contains("Max-Age=0");
        assertThat(refresh(second).getStatus()).isEqualTo(401);
        relay.relayBatch();
        assertThat(jdbc.sql(
                                "select count(*) from audit_event where action = 'SESSION_REFRESH_REUSED' and correlation_id = :c")
                        .param("c", reuse.getHeader("X-Correlation-Id"))
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void logoutRevokesTheSessionAndTheAccessTokenStopsWorkingImmediately() throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.betaUser(jdbc, wallet.address());
        String accessToken = auth.accessToken(auth.login(wallet));

        assertThat(logout(accessToken, UUID.randomUUID()).getStatus()).isEqualTo(204);
        assertThat(logout(accessToken, UUID.randomUUID()).getStatus())
                .as("the access token of a revoked session is refused within its 15 minutes")
                .isEqualTo(401);
    }

    @Test
    void repeatingLogoutWithTheSameKeyReplays204() throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.betaUser(jdbc, wallet.address());
        MockHttpServletResponse login = auth.login(wallet);
        String accessToken = auth.accessToken(login);
        UUID key = UUID.randomUUID();

        assertThat(logout(accessToken, key).getStatus()).isEqualTo(204);
        assertThat(jdbc.sql("select count(*) from idempotency_record where idempotency_key = :k")
                        .param("k", key)
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void aDisabledUserIsRefusedOnTheNextRequestAndOnRefresh() throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.Seeded seeded = TestAccounts.betaUser(jdbc, wallet.address());
        MockHttpServletResponse login = auth.login(wallet);
        TestAccounts.setUserStatus(jdbc, seeded.userId(), "DISABLED");

        assertThat(logout(auth.accessToken(login), UUID.randomUUID()).getStatus())
                .isEqualTo(401);
        assertThat(refresh(AuthFlow.refreshToken(login)).getStatus()).isEqualTo(401);
    }

    @Test
    void refreshAfterThe12HourLimitIsRefused() throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.betaUser(jdbc, wallet.address());
        String token = AuthFlow.refreshToken(auth.login(wallet));
        clock.advance(Duration.ofHours(7));
        token = AuthFlow.refreshToken(refresh(token));
        clock.advance(Duration.ofHours(5).plusSeconds(1));

        assertThat(refresh(token).getStatus()).isEqualTo(401);
    }

    @Test
    void aRealModeRequestIs403AndAuditedAsRequestForbidden() throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.betaUser(jdbc, wallet.address());
        String accessToken = auth.accessToken(auth.login(wallet));

        MockHttpServletResponse response = mvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"REAL\"}"))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(403);
        relay.relayBatch();
        assertThat(jdbc.sql("select action from audit_event where correlation_id = :c")
                        .param("c", response.getHeader("X-Correlation-Id"))
                        .query(String.class)
                        .list())
                .containsExactly("REQUEST_FORBIDDEN");
    }

    private MockHttpServletResponse refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("mt_refresh", refreshToken)))
                .andReturn()
                .getResponse();
    }

    private MockHttpServletResponse logout(String accessToken, UUID key) throws Exception {
        return mvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Idempotency-Key", key.toString()))
                .andReturn()
                .getResponse();
    }
}
