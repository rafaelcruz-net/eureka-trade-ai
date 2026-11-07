package com.memetrade.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base of the U9 integration tests (the live channel of the dashboard, its cap per person and the measurements of the
 * dashboard opening): the U5 base (real PostgreSQL with every migration, application login, fixed clock, no scheduled
 * job, the market doubles of U5) with the {@code u9} tag, so {@code -Dgroups=u9} runs only this Unit. A test signs
 * in with a synthetic wallet generated in the test code; nothing real is used.
 */
@Tag("u9")
public abstract class AbstractU9IT extends AbstractU5IT {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @LocalServerPort
    protected int port;

    @Autowired
    private com.memetrade.identity.domain.ratelimit.LoginRateLimiter loginRateLimiter;

    /** A person of a test session: who it is (for the test fixtures) and its Authorization header. */
    public record Session(Principal person, String bearer) {}

    /** A new BETA_USER person signed in through the real C1 flow; the value is the Authorization header. */
    protected String signIn() throws Exception {
        return newSession().bearer();
    }

    /**
     * A new BETA_USER person signed in through the real C1 flow. The measurements sign in 20 synthetic people within
     * the same minute of the fixed clock, so the per-IP login counter is emptied before each one, as the base does
     * before each test; the limit itself is never changed.
     */
    protected Session newSession() throws Exception {
        loginRateLimiter.clear();
        TestWallet wallet = TestWallet.generate();
        TestAccounts.Seeded seeded = TestAccounts.betaUser(jdbc, wallet.address());
        AuthFlow flow = new AuthFlow(mvc, json);
        String bearer = "Bearer " + flow.accessToken(flow.login(wallet));
        return new Session(new Principal(seeded.tenantId(), seeded.userId(), Role.BETA_USER), bearer);
    }
}
