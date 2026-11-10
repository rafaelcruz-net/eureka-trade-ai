package com.memetrade.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.TestInvites;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Passo 2 of u5-anonymization, written BEFORE the code (BR5.7; NFR2.2; AC6.3.1, AC6.3.2): the access token issued before
 * the anonymization (valid for up to 15 minutes) is refused on the next request, the login and the refresh are refused,
 * and a new invitation with the same wallet creates a NEW account without any history of the old one.
 */
class AnonymizedAccessIT extends AbstractAnonymizationIT {

    @Test
    void theAccessTokenIssuedBeforeIsRefusedOnTheNextRequest() throws Exception {
        Deletion deletion = deletionInProgress();
        assertThat(account(deletion.guest().bearer()).getStatus()).isEqualTo(200);

        anonymize(deletion);

        assertThat(account(deletion.guest().bearer()).getStatus()).isEqualTo(401);
        assertThat(calls.list(deletion.guest().bearer()).getStatus()).isEqualTo(401);
    }

    @Test
    void theLoginWithTheSameWalletIsRefusedAsAnUnknownAccount() throws Exception {
        Deletion deletion = deletionInProgress();

        anonymize(deletion);
        MockHttpServletResponse login =
                new AuthFlow(mvc, json).login(deletion.guest().wallet());

        assertThat(login.getStatus()).isEqualTo(401);
        assertThat(login.getContentAsString()).doesNotContain("accessToken");
        assertThat(jdbc.sql("""
                                select count(*) from audit_outbox
                                where payload->>'action' = 'LOGIN_REJECTED'
                                  and payload->'details'->>'reason' = 'UNKNOWN_ACCOUNT'
                                """).query(Long.class).single()).isPositive();
    }

    @Test
    void theRefreshTokenOfTheOldSessionIsRefused() throws Exception {
        Deletion deletion = deletionInProgress();
        MockHttpServletResponse login =
                new AuthFlow(mvc, json).login(deletion.guest().wallet());
        String refreshToken = AuthFlow.refreshToken(login);

        anonymize(deletion);
        MockHttpServletResponse refreshed = mvc.perform(
                        post("/api/v1/auth/refresh").cookie(new Cookie("mt_refresh", refreshToken)))
                .andReturn()
                .getResponse();

        assertThat(refreshed.getStatus()).isEqualTo(401);
        assertThat(activeSessions(deletion.userId())).isZero();
    }

    @Test
    void aNewInvitationWithTheSameWalletCreatesANewAccountWithoutTheOldHistory() throws Exception {
        Deletion deletion = deletionInProgress();
        seedBot(deletion.guest().principal(), "ACTIVE");
        anonymize(deletion);
        TestInvites.Created invite = TestInvites.pending(jdbc).insert();

        MockHttpServletResponse created =
                new AuthFlow(mvc, json).createAccount(deletion.guest().wallet(), invite.code());

        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(200);
        UUID newUser = jdbc.sql("select user_id from wallet where address = :a")
                .param("a", deletion.wallet())
                .query(UUID.class)
                .single();
        assertThat(newUser).isNotEqualTo(deletion.userId());
        assertThat(accountStatus(newUser)).isEqualTo("ACTIVE");
        assertThat(accountStatus(deletion.userId())).isEqualTo("ANONYMIZED");
        assertThat(bots(newUser)).isEmpty();
        assertThat(jdbc.sql("select count(*) from data_subject_request where user_id = :u")
                        .param("u", newUser)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    private MockHttpServletResponse account(String bearer) throws Exception {
        return mvc.perform(get("/api/v1/account").header("Authorization", bearer))
                .andReturn()
                .getResponse();
    }
}
