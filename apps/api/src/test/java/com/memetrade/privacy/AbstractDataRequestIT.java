package com.memetrade.privacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.identity.api.Principal;
import com.memetrade.identity.api.Role;
import com.memetrade.testsupport.AbstractU8IT;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.DataRequestCalls;
import com.memetrade.testsupport.TestAccounts;
import com.memetrade.testsupport.TestWallet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Base of the integration tests of u4-data-requests (unit-test-instructions.md): the context of the U8 tests (real
 * PostgreSQL with every migration, the application login, fixed clock, no scheduled job), so no new Spring context is
 * created, plus the calls of P1 and P2 and guests whose test wallet signs the verification messages. Each test creates
 * its own people and requests and counts only its own rows.
 */
@Tag("u4-data-requests")
public abstract class AbstractDataRequestIT extends AbstractU8IT {

    @Autowired
    private com.memetrade.identity.domain.ratelimit.LoginRateLimiter loginRateLimiter;

    protected DataRequestCalls calls;

    @BeforeEach
    void prepareCalls() {
        calls = new DataRequestCalls(mvc, json);
    }

    /** A guest signed in through the real entry, with the test wallet of the account. */
    public record WalletGuest(Guest guest, TestWallet wallet) {
        public String bearer() {
            return guest.bearer();
        }

        public Principal principal() {
            return guest.principal();
        }
    }

    protected WalletGuest newWalletGuest() throws Exception {
        TestWallet wallet = TestWallet.generate();
        TestAccounts.Seeded seeded = TestAccounts.betaUser(jdbc, wallet.address());
        AuthFlow auth = new AuthFlow(mvc, json);
        String token = auth.accessToken(auth.login(wallet));
        loginRateLimiter.clear();
        return new WalletGuest(
                new Guest("Bearer " + token, new Principal(seeded.tenantId(), seeded.userId(), Role.BETA_USER)),
                wallet);
    }

    /** A new access token for the same person, after the fixed clock moved past the 15 minutes of the last one. */
    protected WalletGuest relogin(WalletGuest guest) throws Exception {
        AuthFlow auth = new AuthFlow(mvc, json);
        String token = auth.accessToken(auth.login(guest.wallet()));
        loginRateLimiter.clear();
        return new WalletGuest(new Guest("Bearer " + token, guest.principal()), guest.wallet());
    }

    protected String adminBearer() {
        return sessionOf(newAdministrator()).bearer();
    }

    /** Creates a request of the guest and returns its id (201 expected). */
    protected String created(String bearer, String type) throws Exception {
        MockHttpServletResponse response = calls.create(bearer, type, "Texto sintético de teste " + UUID.randomUUID());
        if (response.getStatus() != 201) {
            throw new IllegalStateException("create answered " + response.getStatus());
        }
        return calls.body(response).get("requestId").asText();
    }

    /** Registers an EMAIL request (201 expected) and returns its id. */
    protected String registered(String adminBearer, String type, UUID userId) throws Exception {
        MockHttpServletResponse response = calls.register(
                adminBearer,
                type,
                "Pedido sintético recebido por e-mail",
                "titular-" + UUID.randomUUID() + "@example.org",
                clock.instant().minusSeconds(3600).toString(),
                userId);
        if (response.getStatus() != 201) {
            throw new IllegalStateException(
                    "register answered " + response.getStatus() + " " + response.getContentAsString());
        }
        return calls.body(response).get("requestId").asText();
    }

    protected JsonNode body(MockHttpServletResponse response, int expectedStatus) throws Exception {
        if (response.getStatus() != expectedStatus) {
            throw new AssertionError("expected " + expectedStatus + " but was " + response.getStatus() + ": "
                    + response.getContentAsString());
        }
        return calls.body(response);
    }

    /** Closes a request directly, as if it had been concluded at {@code at} (tests of the window and of the purge). */
    protected void closeDirectly(String requestId, Instant at) {
        jdbc.sql("""
                        update data_subject_request set status = 'DONE', closed_at = :at, response = 'Feito.'
                        where request_id = :id
                        """)
                .param("at", Timestamp.from(at))
                .param("id", UUID.fromString(requestId))
                .update();
    }

    protected long events(String action, String requestId) {
        return jdbc.sql("""
                        select count(*) from audit_outbox
                        where payload->>'action' = :action and payload->'details'->>'requestId' = :id
                        """)
                .param("action", action)
                .param("id", requestId)
                .query(Long.class)
                .single();
    }

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactions;

    /** NFR3.5: the plan of {@code sql} with sequential scans switched off for that transaction only. */
    protected String explain(String sql) {
        String plan = new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> {
            jdbc.sql("set local enable_seqscan = off").update();
            return String.join(
                    "\n", jdbc.sql("explain " + sql).query(String.class).list());
        });
        return plan == null ? "" : plan;
    }

    protected String status(String requestId) {
        return jdbc.sql("select status from data_subject_request where request_id = :id")
                .param("id", UUID.fromString(requestId))
                .query(String.class)
                .single();
    }
}
