package com.memetrade.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.identity.infra.SmokeAccountSeeder;
import com.memetrade.shared.wallet.WalletAddress;
import com.memetrade.testsupport.AbstractPostgresIT;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.TestWallet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** With the "test" profile the smoke wallet's public address becomes an ACTIVE BETA_USER that can sign in. */
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class SmokeAccountSeederIT extends AbstractPostgresIT {

    private static final TestWallet SMOKE = TestWallet.generate();

    @DynamicPropertySource
    static void smokeWallet(DynamicPropertyRegistry registry) {
        registry.add("memetrade.smoke.beta-wallet-address", SMOKE::address);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private SmokeAccountSeeder seeder;

    @Test
    void theSeededSmokeWalletSignsInAsBetaUserAndTheSeedIsIdempotent(CapturedOutput output) throws Exception {
        seeder.seed();

        assertThat(new AuthFlow(mvc, json).login(SMOKE).getStatus()).isEqualTo(200);
        assertThat(jdbc.sql("select count(*) from wallet where address = :a")
                        .param("a", SMOKE.address())
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(output.getAll())
                .contains("TEST PROFILE ACTIVE")
                .contains(WalletAddress.mask(SMOKE.address()))
                .doesNotContain(SMOKE.address());
    }
}
