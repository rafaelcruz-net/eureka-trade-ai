package com.memetrade.testsupport;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base of the U3 integration tests: the integration base of U1/U2 plus ONE administrator wallet fixed for the whole JVM
 * run, so every U3 test class shares a single Spring context (same properties) instead of starting one per class. The
 * wallet is generated in test code (never production code) and only its public address reaches the configuration.
 */
@Tag("u3")
public abstract class AbstractU3IT extends AbstractPostgresIT {

    /** The administrator wallet of the configuration (BR3.2 check 3: it never creates a guest account). */
    protected static final TestWallet ADMIN_WALLET = TestWallet.generate();

    @DynamicPropertySource
    static void u3Settings(DynamicPropertyRegistry registry) {
        registry.add("memetrade.auth.admin-wallet-addresses", ADMIN_WALLET::address);
    }
}
