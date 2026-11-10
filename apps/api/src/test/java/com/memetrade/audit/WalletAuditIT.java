package com.memetrade.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.audit.api.AuditActions;
import com.memetrade.audit.infra.AuditOutboxRelay;
import com.memetrade.identity.domain.login.AdminLoginService;
import com.memetrade.identity.domain.login.LoginVerificationService;
import com.memetrade.identity.domain.login.LoginVerificationService.VerifyCommand;
import com.memetrade.shared.wallet.WalletAddress;
import com.memetrade.testsupport.AbstractU8IT;
import com.memetrade.testsupport.AdminAuthFlow;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.TestInvites;
import com.memetrade.testsupport.TestWallet;
import com.memetrade.testsupport.TestWalletHmac;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Passo 5 of u2-retention, written before the code (US3.2; AC3.2.1, AC3.2.2; BR2.1, BR2.2, BR2.15, BR2.18): over the
 * real api, a guest account is created with an invitation, signs in and is refused once; an administrator signs in and
 * is refused once. Neither full address appears anywhere in {@code audit_event} or {@code audit_outbox}; every event of
 * the same wallet carries the same HMAC, in the outbox payload and in {@code audit_event.wallet_hmac} after the relay;
 * a refusal with text that is not a wallet is anonymous, without HMAC and without the text.
 */
@Tag("u2-retention")
class WalletAuditIT extends AbstractU8IT {

    @Autowired
    private AuditOutboxRelay relay;

    @Autowired
    private LoginVerificationService guestLogin;

    @Autowired
    private AdminLoginService adminLogin;

    @Test
    void neitherFullAddressReachesTheTrailAndEachWalletKeepsOneHmac() throws Exception {
        TestWallet guest = TestWallet.generate();
        TestWallet administrator = TestWallet.generate();
        AuthFlow auth = new AuthFlow(mvc, json);
        AdminAuthFlow admin = new AdminAuthFlow(mvc, json);
        adminWallets.add(administrator.address());

        String code = TestInvites.pending(jdbc).insert().code();
        assertThat(auth.createAccount(guest, code).getStatus()).isEqualTo(200);
        assertThat(auth.login(guest).getStatus()).isEqualTo(200);
        String guestMessage = auth.challenge(guest).get("message").asText();
        assertThat(auth.verify(guest, guestMessage, TestWallet.generate().sign(guestMessage))
                        .getStatus())
                .isEqualTo(401);
        assertThat(admin.login(administrator).getStatus()).isEqualTo(200);
        String adminMessage = admin.challenge(administrator).get("message").asText();
        assertThat(admin.verify(
                                administrator,
                                adminMessage,
                                TestWallet.generate().sign(adminMessage))
                        .getStatus())
                .isEqualTo(401);
        drainOutbox();

        for (String address : List.of(guest.address(), administrator.address())) {
            assertThat(outboxRowsContaining(address))
                    .as("outbox rows with the full address")
                    .isZero();
            assertThat(trailRowsContaining(address))
                    .as("trail rows with the full address")
                    .isZero();
        }
        String guestHmac = TestWalletHmac.of(guest.address());
        String adminHmac = TestWalletHmac.of(administrator.address());
        assertThat(guestHmac).isNotEqualTo(adminHmac);
        assertThat(actionsInOutbox(guestHmac))
                .contains(AuditActions.ACCOUNT_CREATED, AuditActions.LOGIN_SUCCEEDED, AuditActions.LOGIN_REJECTED);
        assertThat(actionsInOutbox(adminHmac)).contains(AdminLoginService.ADMIN_LOGIN, AuditActions.LOGIN_REJECTED);
        assertThat(actionsInTrail(guestHmac))
                .contains(AuditActions.ACCOUNT_CREATED, AuditActions.LOGIN_SUCCEEDED, AuditActions.LOGIN_REJECTED);
        assertThat(actionsInTrail(adminHmac)).contains(AdminLoginService.ADMIN_LOGIN, AuditActions.LOGIN_REJECTED);
        assertThat(maskedActorsOfRejections(guestHmac)).containsOnly(WalletAddress.mask(guest.address()));
    }

    @Test
    void aGuestRefusalWithTextThatIsNotAWalletIsAnonymousWithoutHmacAndWithoutTheText() throws Exception {
        String correlation = UUID.randomUUID().toString();
        String notAWallet = "not-a-wallet-" + UUID.randomUUID();

        guestLogin.verify(new VerifyCommand(notAWallet, "sign in please", "sig", "PHANTOM", correlation));

        JsonNode event = onlyEventOf(correlation);
        assertThat(event.get("actor").asText()).isEqualTo("anonymous");
        assertThat(event.has("walletHmac")).isFalse();
        assertThat(event.get("details").get("addressFormat").asText()).isEqualTo("INVALID");
        assertThat(event.toString()).doesNotContain(notAWallet);
    }

    @Test
    void anAdministratorRefusalWithTextThatIsNotAWalletIsAnonymousWithoutHmac() throws Exception {
        String correlation = UUID.randomUUID().toString();

        adminLogin.recordRateLimited("??", correlation);

        JsonNode event = onlyEventOf(correlation);
        assertThat(event.get("actor").asText()).isEqualTo("anonymous");
        assertThat(event.has("walletHmac")).isFalse();
        assertThat(event.get("details").get("addressFormat").asText()).isEqualTo("INVALID");
        assertThat(event.get("details").get("reason").asText()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void aRelayedEventWithoutAWalletLeavesTheColumnEmpty() throws Exception {
        String correlation = UUID.randomUUID().toString();
        guestLogin.verify(new VerifyCommand("x", "sign in please", "sig", "PHANTOM", correlation));
        drainOutbox();

        List<String> hmacs = new ArrayList<>();
        jdbc.sql("select wallet_hmac from audit_event where correlation_id = :c")
                .param("c", correlation)
                .query((rs, row) -> hmacs.add(rs.getString("wallet_hmac")))
                .list();

        assertThat(hmacs).hasSize(1).containsOnlyNulls();
    }

    private void drainOutbox() {
        for (int batch = 0; batch < 1000; batch++) {
            if (relay.relayBatch() == 0) {
                return;
            }
        }
        throw new IllegalStateException("The audit outbox still has events after 1000 batches");
    }

    private long outboxRowsContaining(String address) {
        return jdbc.sql("select count(*) from audit_outbox where strpos(payload::text, :a) > 0")
                .param("a", address)
                .query(Long.class)
                .single();
    }

    private long trailRowsContaining(String address) {
        return jdbc.sql("""
                        select count(*) from audit_event
                        where strpos(details::text, :a) > 0 or actor = :a or strpos(coalesce(target_ref, ''), :a) > 0
                        """).param("a", address).query(Long.class).single();
    }

    private List<String> actionsInOutbox(String hmac) {
        return jdbc.sql("select payload->>'action' from audit_outbox where payload->>'walletHmac' = :h")
                .param("h", hmac)
                .query(String.class)
                .list();
    }

    private List<String> actionsInTrail(String hmac) {
        return jdbc.sql("select action from audit_event where wallet_hmac = :h")
                .param("h", hmac)
                .query(String.class)
                .list();
    }

    private List<String> maskedActorsOfRejections(String hmac) {
        return jdbc.sql("select actor from audit_event where wallet_hmac = :h and action = 'LOGIN_REJECTED'")
                .param("h", hmac)
                .query(String.class)
                .list();
    }

    private JsonNode onlyEventOf(String correlation) throws Exception {
        List<String> payloads = jdbc.sql("select payload::text from audit_outbox where payload->>'correlationId' = :c")
                .param("c", correlation)
                .query(String.class)
                .list();
        assertThat(payloads).hasSize(1);
        return json.readTree(payloads.getFirst());
    }
}
