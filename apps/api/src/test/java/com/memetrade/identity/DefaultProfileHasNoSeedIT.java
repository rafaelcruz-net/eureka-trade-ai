package com.memetrade.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.memetrade.identity.infra.SmokeAccountSeeder;
import com.memetrade.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

class DefaultProfileHasNoSeedIT extends AbstractPostgresIT {

    @Autowired
    private ApplicationContext context;

    /** U3 Passo 16: the smoke invitation seeder does not exist outside the test profile either. */
    @Test
    @org.junit.jupiter.api.Tag("u3")
    void outsideTheTestProfileTheInvitationSeederDoesNotExist() {
        assertThat(context.getBeansOfType(com.memetrade.identity.infra.SmokeInviteSeeder.class))
                .isEmpty();
    }

    @Test
    void outsideTheTestProfileTheSeedComponentDoesNotExist() {
        assertThat(context.getBeansOfType(SmokeAccountSeeder.class)).isEmpty();
        assertThat(context.getEnvironment().getProperty("memetrade.auth.login-rate-limit-per-minute"))
                .isEqualTo("10");
    }
}
