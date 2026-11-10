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

    /**
     * u3-reacceptance (infrastructure-specification.md "Dados de teste para a trava"): the smoke account has ONE
     * REACCEPTANCE of the effective current version, without an invitation, however many times the seed runs; the
     * reacceptance lock lets it through.
     */
    @Test
    void theSmokeAccountAcceptsTheCurrentVersionOnceHoweverManyTimesTheSeedRuns() throws Exception {
        seeder.seed();
        seeder.seed();

        assertThat(acceptances()).containsExactly("v1|REACCEPTANCE|null");
        AuthFlow auth = new AuthFlow(mvc, json);
        String token = auth.accessToken(auth.login(SMOKE));
        assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/bots")
                                .header("Authorization", "Bearer " + token))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
    }

    /**
     * The acceptance step runs outside the early return of the account creation: an account that already exists
     * without the acceptance of the current version gets it on the next start.
     */
    @Test
    void anExistingSmokeAccountWithoutTheCurrentAcceptanceGetsItOnTheNextStart() {
        TestWallet other = TestWallet.generate();
        com.memetrade.testsupport.TestAccounts.betaUserWithoutAcceptance(jdbc, other.address());
        SmokeAccountSeeder forOther = new SmokeAccountSeeder(
                jdbc,
                new org.springframework.transaction.support.TransactionTemplate(transactionManager),
                clock,
                currentTerms,
                other.address());

        forOther.seed();
        forOther.seed();

        assertThat(jdbc.sql("""
                                select count(*) from terms_acceptance t join wallet w on w.user_id = t.user_id
                                where w.address = :a and t.terms_version = 'v1' and t.source = 'REACCEPTANCE'
                                """).param("a", other.address()).query(Long.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from wallet where address = :a")
                        .param("a", other.address())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Autowired
    private com.memetrade.identity.domain.terms.CurrentTerms currentTerms;

    private java.util.List<String> acceptances() {
        return jdbc.sql("""
                        select t.terms_version, t.source, t.invite_id from terms_acceptance t
                        join wallet w on w.user_id = t.user_id
                        where w.address = :a
                        """)
                .param("a", SMOKE.address())
                .query((rs, n) -> rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3))
                .list();
    }
}
