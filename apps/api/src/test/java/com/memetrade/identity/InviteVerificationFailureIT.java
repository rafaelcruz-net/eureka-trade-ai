package com.memetrade.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memetrade.audit.api.AuditActions;
import com.memetrade.audit.api.AuditEventPublisher;
import com.memetrade.identity.domain.account.GuestAccountRepository;
import com.memetrade.identity.domain.session.SessionRepository;
import com.memetrade.testsupport.AbstractU3IT;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.OutboxEvents;
import com.memetrade.testsupport.TestInvites;
import com.memetrade.testsupport.TestWallet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Passo 10 (NFR4.1, NFR10.2, BR3.3, BR3.6): a technical failure at the insertion of the acceptance, of the
 * ACCOUNT_CREATED event or of the session rolls the whole verification back: no new row, the invitation stays PENDING,
 * the challenge is NOT consumed (still ISSUED), no LOGIN_REJECTED, and the answer is 5xx with a correlation id.
 */
class InviteVerificationFailureIT extends AbstractU3IT {

    @MockitoSpyBean
    private GuestAccountRepository guests;

    @MockitoSpyBean
    private AuditEventPublisher audit;

    @MockitoSpyBean
    private SessionRepository sessions;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @AfterEach
    void restore() {
        reset(guests, audit, sessions);
    }

    @Test
    void aFailureWritingTheAcceptanceUndoesEverything() throws Exception {
        doThrow(new IllegalStateException("injected")).when(guests).recordAcceptance(any());

        assertEverythingUndone();
    }

    @Test
    void aFailureWritingTheAccountCreatedEventUndoesEverything() throws Exception {
        doThrow(new IllegalStateException("injected"))
                .when(audit)
                .publish(argThat(event -> AuditActions.ACCOUNT_CREATED.equals(event.action())));

        assertEverythingUndone();
    }

    @Test
    void aDatabaseFailureOpeningTheSessionUndoesEverythingAndAnswers503() throws Exception {
        doThrow(new DataAccessResourceFailureException("injected"))
                .when(sessions)
                .insert(any());

        MockHttpServletResponse response = assertEverythingUndone();

        assertThat(response.getStatus()).isEqualTo(503);
    }

    private MockHttpServletResponse assertEverythingUndone() throws Exception {
        TestInvites.Created invite = TestInvites.pending(jdbc).insert();
        TestWallet wallet = TestWallet.generate();
        AuthFlow auth = new AuthFlow(mvc, json);
        JsonNode challenge = auth.challenge(wallet);
        String message = challenge.get("message").asText();

        MockHttpServletResponse response =
                auth.verifyWithInvite(wallet, message, wallet.sign(message), invite.code(), "v1");

        assertThat(response.getStatus()).isBetween(500, 599);
        JsonNode problem = json.readTree(response.getContentAsString());
        assertThat(problem.get("correlationId").asText()).isNotBlank();
        assertThat(response.getContentAsString()).doesNotContain("injected");
        assertThat(count("select count(*) from wallet where address = :p", wallet.address()))
                .isZero();
        assertThat(count("select count(*) from app_user where tenant_id = cast(:p as uuid)", invite.tenantId()))
                .isZero();
        assertThat(count("select count(*) from terms_acceptance where invite_id = cast(:p as uuid)", invite.inviteId()))
                .isZero();
        assertThat(jdbc.sql("select status from invite where invite_id = :id")
                        .param("id", invite.inviteId())
                        .query(String.class)
                        .single())
                .isEqualTo("PENDING");
        assertThat(jdbc.sql("select state from login_challenge where nonce = :n")
                        .param("n", challenge.get("nonce").asText())
                        .query(String.class)
                        .single())
                .as("nothing consumed: the challenge keeps its own deadline")
                .isEqualTo("ISSUED");
        assertThat(OutboxEvents.count(jdbc, AuditActions.ACCOUNT_CREATED, "invite:" + invite.inviteId()))
                .isZero();
        assertThat(OutboxEvents.reasonsForWallet(jdbc, AuditActions.LOGIN_REJECTED, wallet.address()))
                .isEmpty();
        return response;
    }

    private long count(String sql, Object parameter) {
        return jdbc.sql(sql).param("p", parameter.toString()).query(Long.class).single();
    }
}
