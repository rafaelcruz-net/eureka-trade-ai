package com.memetrade.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.identity.domain.invite.InviteCodes;
import com.memetrade.identity.domain.invite.InviteRepository;
import com.memetrade.identity.infra.SmokeInviteSeeder;
import com.memetrade.testsupport.AbstractU3IT;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.TestInvites;
import com.memetrade.testsupport.TestWallet;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Passo 16 (Q1 of the Infrastructure Design, NFR3.4): with the test profile the smoke code and the load codes become
 * PENDING invitations of 1 hour in the tenant "Beta" at start-up; seeding again changes nothing; logs show only the
 * last 4 characters; a malformed code stops the start; empty settings create nothing.
 */
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class SmokeInviteSeederIT extends AbstractU3IT {

    private static final TestWallet SMOKE_WALLET = TestWallet.generate();
    private static final String SMOKE_CODE = TestInvites.newCode();
    private static final String LOAD_A = TestInvites.newCode();
    private static final String LOAD_B = TestInvites.newCode();

    @DynamicPropertySource
    static void smokeSettings(DynamicPropertyRegistry registry) {
        registry.add("memetrade.smoke.beta-wallet-address", SMOKE_WALLET::address);
        registry.add("memetrade.smoke.invite-code", () -> SMOKE_CODE);
        registry.add("memetrade.smoke.invite-codes", () -> " " + LOAD_A + "," + LOAD_B + ",," + SMOKE_CODE + " ");
    }

    @Autowired
    private SmokeInviteSeeder seeder;

    @Autowired
    private InviteRepository invites;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Test
    void theSmokeAndLoadCodesArePendingInvitationsOfOneHourInTheTenantBeta() {
        for (String code : new String[] {SMOKE_CODE, LOAD_A, LOAD_B}) {
            Map<String, Object> row =
                    jdbc.sql("""
                            select tenant_id, status, extract(epoch from (expires_at - created_at)) as validity
                            from invite where code_digest = :d
                            """).param("d", InviteCodes.digest(code)).query().singleRow();
            assertThat(row.get("tenant_id")).isEqualTo(TestInvites.BETA_TENANT);
            assertThat(row.get("status")).isEqualTo("PENDING");
            assertThat(((Number) row.get("validity")).longValue())
                    .isEqualTo(Duration.ofHours(1).toSeconds());
        }
        assertThat(seeder.codes()).containsExactly(SMOKE_CODE, LOAD_A, LOAD_B);
    }

    @Test
    void seedingAgainNeverDuplicatesAndTheLogShowsOnlyTheLastFourCharacters(CapturedOutput output) {
        seeder.seed();

        assertThat(jdbc.sql("select count(*) from invite where code_digest = :d")
                        .param("d", InviteCodes.digest(SMOKE_CODE))
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(output.getAll())
                .contains("TEST PROFILE ACTIVE: smoke invitation ending " + SMOKE_CODE.substring(39))
                .contains("already present")
                .doesNotContain(SMOKE_CODE)
                .doesNotContain(LOAD_A);
    }

    @Test
    void theSeededSmokeCodeCreatesAnAccountThroughTheGuestEntry() throws Exception {
        // Seeded at start-up with the injected clock; every test starts at that same instant, inside the hour.
        assertThat(new AuthFlow(mvc, json)
                        .createAccount(TestWallet.generate(), LOAD_B)
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    void aMalformedCodeStopsTheStartWithAMessageThatNeverEchoesIt() {
        String malformed = SMOKE_CODE.substring(1);

        assertThatThrownBy(() -> new SmokeInviteSeeder(invites, transactions, clock, malformed, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMOKE_INVITE_CODE")
                .hasMessageNotContaining(malformed);
        assertThatThrownBy(() -> new SmokeInviteSeeder(invites, transactions, clock, "", LOAD_A + ",bad"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void emptySettingsCreateNothing() {
        long before = jdbc.sql("select count(*) from invite").query(Long.class).single();
        SmokeInviteSeeder empty = new SmokeInviteSeeder(invites, transactions, clock, "", " , ");

        empty.seed();

        assertThat(empty.codes()).isEmpty();
        assertThat(jdbc.sql("select count(*) from invite").query(Long.class).single())
                .isEqualTo(before);
        assertThat(new SmokeInviteSeeder(invites, transactions, clock, null, null).codes())
                .isEmpty();
    }
}
