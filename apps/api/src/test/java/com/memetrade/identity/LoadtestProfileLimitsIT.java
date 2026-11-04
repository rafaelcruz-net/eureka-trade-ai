package com.memetrade.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.domain.ratelimit.InviteLookupRateLimiter;
import com.memetrade.identity.domain.ratelimit.LoginRateLimiter;
import com.memetrade.testsupport.AbstractU3IT;
import com.memetrade.testsupport.TestWallet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Passo 8 (NFR6.6, performance-design.md): only the loadtest profile, which starts only with the test profile, raises
 * the invitation lookup limit (default 1000 there), as it does for the login limit; the default profile keeps 10
 * ({@code U3ContextIT}).
 */
@ActiveProfiles({"test", "loadtest"})
class LoadtestProfileLimitsIT extends AbstractU3IT {

    private static final TestWallet SMOKE = TestWallet.generate();

    @DynamicPropertySource
    static void smokeWallet(DynamicPropertyRegistry registry) {
        registry.add("memetrade.smoke.beta-wallet-address", SMOKE::address);
    }

    @Autowired
    private InviteLookupRateLimiter inviteLookups;

    @Autowired
    private LoginRateLimiter login;

    @Test
    void theLoadtestProfileRaisesTheInvitationLookupLimitLikeTheLoginLimit() {
        assertThat(inviteLookups.limit()).isEqualTo(1000);
        assertThat(login.limit()).isEqualTo(1000);
    }
}
