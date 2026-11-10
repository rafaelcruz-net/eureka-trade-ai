package com.memetrade.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.infra.IdentityProperties;
import com.memetrade.identity.infra.TermsProperties;
import com.memetrade.testsupport.AbstractU3IT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** The U3 command runs green before the first test of the Unit: the context starts with the U3 settings (Passo 2). */
class U3ContextIT extends AbstractU3IT {

    @Autowired
    private IdentityProperties identity;

    @Autowired
    private TermsProperties terms;

    @Test
    void theContextStartsWithTheU3DefaultsOfTheInfrastructureSpecification() throws Exception {
        assertThat(identity.inviteLookupRateLimitPerMinute()).isEqualTo(10);
        assertThat(identity.loginRateLimitPerMinute()).isEqualTo(10);
        assertThat(identity.adminWalletAddresses()).containsExactly(ADMIN_WALLET.address());
        assertThat(terms.location()).isEqualTo("classpath:terms/");
        assertThat(currentTerms.current().version()).isEqualTo("v1");
    }

    @Autowired
    private com.memetrade.identity.domain.terms.CurrentTerms currentTerms;

    @Test
    void onlyTheLoadtestProfileReadsTheVariableThatRaisesTheInvitationLookupLimit() throws Exception {
        assertThat(yaml("application.yml").getProperty("memetrade.auth.invite-lookup-rate-limit-per-minute"))
                .hasToString("10");
        assertThat(yaml("application-loadtest.yml").getProperty("memetrade.auth.invite-lookup-rate-limit-per-minute"))
                .hasToString("${INVITE_LOOKUP_RATE_LIMIT_PER_MINUTE:1000}");
        assertThat(yaml("application-test.yml").getProperty("memetrade.smoke.invite-code"))
                .hasToString("${SMOKE_INVITE_CODE:}");
        assertThat(yaml("application-test.yml").getProperty("memetrade.smoke.invite-codes"))
                .hasToString("${SMOKE_INVITE_CODES:}");
        assertThat(yaml("application-test.yml").getProperty("memetrade.smoke.anonymization-invite-code"))
                .hasToString("${SMOKE_ANONYMIZATION_INVITE_CODE:}");
    }

    private static PropertySource<?> yaml(String file) throws Exception {
        return new YamlPropertySourceLoader()
                .load(file, new ClassPathResource(file))
                .getFirst();
    }
}
