package com.memetrade.identity.infra;

import com.memetrade.identity.api.RealIdentity;
import com.memetrade.identity.domain.realterms.MainWalletLookup;
import com.memetrade.identity.domain.realterms.RealIdentityService;
import com.memetrade.identity.domain.signature.Ed25519SignatureVerifier;
import com.memetrade.identity.domain.terms.TermsAcceptanceLookup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Wiring of M9 (261113-trading-real-bot): the main wallet comes from the wallet verified at login, by tenant. */
@Configuration
public class RealIdentityConfig {

    @Bean
    MainWalletLookup mainWalletLookup(JdbcClient jdbc) {
        return (tenantId, userId) -> jdbc.sql("""
                        select w.address from wallet w join app_user u on u.user_id = w.user_id
                        where u.user_id = :user and u.tenant_id = :tenant and w.address is not null""")
                .param("user", userId)
                .param("tenant", tenantId)
                .query(String.class)
                .optional();
    }

    @Bean
    RealIdentity realIdentity(
            TermsAcceptanceLookup acceptances,
            MainWalletLookup wallets,
            @Value("${memetrade.real.required-terms-version}") String requiredVersion) {
        return new RealIdentityService(acceptances, wallets, new Ed25519SignatureVerifier(), requiredVersion);
    }
}
