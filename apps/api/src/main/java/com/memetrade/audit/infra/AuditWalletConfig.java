package com.memetrade.audit.infra;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * u2-retention (NFR6.1, NFR7.5): the wallet hasher of the audit trail, in the api and in the worker (both publish
 * events), and the actuator sanitizer of its key. The key is read from {@code memetrade.audit.wallet-hmac-key}
 * ({@code MEMETRADE_AUDIT_WALLET_HMAC_KEY}); without it, or with fewer than 32 bytes, the context does not start.
 */
@Configuration(proxyBeanMethods = false)
public class AuditWalletConfig {

    @Bean
    public WalletHasher walletHasher(@Value("${" + WalletHasher.PROPERTY + ":}") String key) {
        return new WalletHasher(key);
    }

    @Bean
    public WalletHmacKeySanitizer walletHmacKeySanitizer() {
        return new WalletHmacKeySanitizer();
    }
}
