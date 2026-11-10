package com.memetrade.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.memetrade.audit.api.AuditActions;
import com.memetrade.identity.api.PersonDataPort;
import com.memetrade.testsupport.AuthFlow;
import com.memetrade.testsupport.TestWallet;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Passo 6 of u4-data-requests (F3; BR4.11 to BR4.13; NFR2.3; AC5.6.2 to AC5.6.8): the signature verification on the real
 * api with a wallet generated in test code. The signature of the wallet of the account confirms once; another wallet,
 * a repeated or an expired confirmation are refused; a verification signature sent to the login is refused; the
 * signature is never stored nor published.
 */
class IdentityVerificationIT extends AbstractDataRequestIT {

    private record Started(String requestId, String message, Instant expiresAt) {}

    private Started started(String admin, WalletGuest guest, String type) throws Exception {
        String id = registered(admin, type, guest.principal().userId());
        JsonNode verification = body(calls.startVerification(admin, id), 200);
        return new Started(
                id,
                verification.get("message").asText(),
                Instant.parse(verification.get("expiresAt").asText()));
    }

    @Test
    void theSignatureOfTheWalletOfTheAccountConfirmsTheIdentityAndTheSummaryIsReleased() throws Exception {
        WalletGuest guest = newWalletGuest();
        String admin = adminBearer();
        Started started = started(admin, guest, "ACCESS");

        JsonNode waiting = body(calls.adminGet(admin, started.requestId()), 200);
        JsonNode confirmed =
                body(calls.confirm(admin, started.requestId(), guest.wallet().sign(started.message())), 200);
        JsonNode done = body(calls.transition(admin, started.requestId(), "DONE", "Resumo enviado.", null, true), 200);

        assertThat(started.message()).startsWith(PersonDataPort.VERIFICATION_FIRST_LINE + "\n");
        assertThat(started.message()).contains(started.requestId());
        assertThat(started.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofHours(24)));
        assertThat(waiting.get("status").asText()).isEqualTo("AWAITING_VERIFICATION");
        assertThat(waiting.get("verification").get("state").asText()).isEqualTo("PENDING");
        assertThat(confirmed.get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(confirmed.get("identityVerified").asBoolean()).isTrue();
        assertThat(confirmed.get("verification").get("state").asText()).isEqualTo("CONFIRMED");
        assertThat(done.get("accessSummary").get("walletMasked").asText())
                .isNotEqualTo(guest.wallet().address());
        assertThat(events(AuditActions.DATA_REQUEST_VERIFICATION_STARTED, started.requestId()))
                .isEqualTo(1);
        assertThat(events(AuditActions.DATA_REQUEST_VERIFIED, started.requestId()))
                .isEqualTo(1);
    }

    @Test
    void theSignatureOfAnotherWalletIs422AndTheRequestKeepsWaiting() throws Exception {
        WalletGuest guest = newWalletGuest();
        String admin = adminBearer();
        Started started = started(admin, guest, "DELETION");

        MockHttpServletResponse refused =
                calls.confirm(admin, started.requestId(), TestWallet.generate().sign(started.message()));

        assertThat(refused.getStatus()).isEqualTo(422);
        assertThat(calls.body(refused).get("code").asText()).isEqualTo("VERIFICATION_INVALID");
        assertThat(status(started.requestId())).isEqualTo("AWAITING_VERIFICATION");
        assertThat(calls.confirm(admin, started.requestId(), "not-a-signature").getStatus())
                .isEqualTo(422);
    }

    @Test
    void aRepeatedConfirmationIs409AlreadyUsed() throws Exception {
        WalletGuest guest = newWalletGuest();
        String admin = adminBearer();
        Started started = started(admin, guest, "ACCESS");
        String signature = guest.wallet().sign(started.message());
        body(calls.confirm(admin, started.requestId(), signature), 200);

        MockHttpServletResponse again = calls.confirm(admin, started.requestId(), signature);

        assertThat(again.getStatus()).isEqualTo(409);
        assertThat(calls.body(again).get("code").asText()).isEqualTo("VERIFICATION_ALREADY_USED");
    }

    @Test
    void aConfirmationAfterTwentyFourHoursIs409Expired() throws Exception {
        WalletGuest guest = newWalletGuest();
        String admin = adminBearer();
        Started started = started(admin, guest, "ACCESS");
        clock.advance(Duration.ofHours(24));

        MockHttpServletResponse late =
                calls.confirm(admin, started.requestId(), guest.wallet().sign(started.message()));

        assertThat(late.getStatus()).isEqualTo(409);
        assertThat(calls.body(late).get("code").asText()).isEqualTo("VERIFICATION_EXPIRED");
    }

    @Test
    void theVerificationSignatureSentToTheLoginIsRefused() throws Exception {
        WalletGuest guest = newWalletGuest();
        String admin = adminBearer();
        Started started = started(admin, guest, "ACCESS");
        String signature = guest.wallet().sign(started.message());

        MockHttpServletResponse login = new AuthFlow(mvc, json).verify(guest.wallet(), started.message(), signature);

        assertThat(login.getStatus()).isEqualTo(401);
        assertThat(login.getContentAsString()).doesNotContain(signature);
    }

    @Test
    void aLoginSignatureNeverConfirmsAVerification() throws Exception {
        WalletGuest guest = newWalletGuest();
        String admin = adminBearer();
        Started started = started(admin, guest, "ACCESS");
        JsonNode challenge = new AuthFlow(mvc, json).challenge(guest.wallet());
        String loginSignature = guest.wallet().sign(challenge.get("message").asText());

        MockHttpServletResponse refused = calls.confirm(admin, started.requestId(), loginSignature);

        assertThat(refused.getStatus()).isEqualTo(422);
        assertThat(status(started.requestId())).isEqualTo("AWAITING_VERIFICATION");
    }

    @Test
    void anAccountRequestOrAnEmailWithoutAccountCannotStartAVerification() throws Exception {
        WalletGuest guest = newWalletGuest();
        String admin = adminBearer();
        String account = created(guest.bearer(), "ACCESS");
        String withoutAccount = registered(admin, "ACCESS", null);
        String correction = registered(admin, "CORRECTION", guest.principal().userId());

        for (String id : new String[] {account, withoutAccount, correction}) {
            MockHttpServletResponse refused = calls.startVerification(admin, id);
            assertThat(refused.getStatus()).as(id).isEqualTo(409);
            assertThat(calls.body(refused).get("code").asText()).isEqualTo("INVALID_TRANSITION");
        }
    }

    @Test
    void theManualRefusalExpiresTheVerificationAndAClosedRequestIs409ClosedFirst() throws Exception {
        WalletGuest guest = newWalletGuest();
        String admin = adminBearer();
        Started started = started(admin, guest, "ACCESS");

        body(calls.transition(admin, started.requestId(), "REFUSED", null, "Carteira perdida.", false), 200);
        MockHttpServletResponse late =
                calls.confirm(admin, started.requestId(), guest.wallet().sign(started.message()));

        assertThat(late.getStatus()).isEqualTo(409);
        assertThat(calls.body(late).get("code").asText()).isEqualTo("DATA_REQUEST_CLOSED");
        assertThat(jdbc.sql("select state from data_subject_verification where request_id = :id")
                        .param("id", UUID.fromString(started.requestId()))
                        .query(String.class)
                        .single())
                .isEqualTo("EXPIRED");
    }

    @Test
    void theSignatureIsNeverStoredNorPublished() throws Exception {
        WalletGuest guest = newWalletGuest();
        String admin = adminBearer();
        Started started = started(admin, guest, "ACCESS");
        String signature = guest.wallet().sign(started.message());

        body(calls.confirm(admin, started.requestId(), signature), 200);

        assertThat(jdbc.sql("select count(*) from audit_outbox where strpos(payload::text, :s) > 0")
                        .param("s", signature)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(jdbc.sql("""
                                select count(*) from data_subject_verification v
                                join data_subject_request r on r.request_id = v.request_id
                                where strpos(v.message, :s) > 0 or strpos(r.details, :s) > 0
                                """).param("s", signature).query(Long.class).single())
                .isZero();
        assertThat(jdbc.sql("""
                                select count(*) from idempotency_record
                                where strpos(coalesce(response_body::text, ''), :s) > 0
                                """).param("s", signature).query(Long.class).single())
                .isZero();
    }
}
